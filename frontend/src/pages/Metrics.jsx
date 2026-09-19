import { useState, useEffect, useRef } from 'react';
import { usePolling } from '../hooks/usePolling';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import LoadingSpinner from '../components/LoadingSpinner';
import { RefreshCw, ExternalLink } from 'lucide-react';
import {
  AreaChart,
  Area,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
  PieChart,
  Pie,
  Cell,
} from 'recharts';

const COLORS = ['#10b981', '#ef4444', '#f59e0b', '#6366f1', '#06b6d4', '#ec4899'];
const STATUS_COLORS = { UP: '#10b981', DOWN: '#ef4444', DRAINING: '#f59e0b', DISABLED: '#9ca3af' };

export default function MetricsPage() {
  const { data: status, loading: statusLoading } = usePolling(api.getStatus, 3000);
  const { data: backends, loading: backendsLoading } = usePolling(api.listBackends, 3000);

  // Time-series tracking
  const historyRef = useRef([]);
  const prevStatusRef = useRef(null);

  useEffect(() => {
    if (!status) return;
    const now = new Date();
    const timeLabel = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    const prev = prevStatusRef.current;
    const rps = prev ? Math.max(0, status.totalRequests - prev.totalRequests) : 0;
    const failedDelta = prev ? Math.max(0, status.failedRequests - prev.failedRequests) : 0;

    historyRef.current = [
      ...historyRef.current.slice(-59),
      {
        time: timeLabel,
        requests: rps,
        failed: failedDelta,
        active: status.activeRequests,
        healthy: status.healthyBackends,
        unhealthy: status.totalBackends - status.healthyBackends,
        circuits: status.openCircuits,
      },
    ];
    prevStatusRef.current = status;
  }, [status]);

  if ((statusLoading && !status) || (backendsLoading && !backends)) return <LoadingSpinner />;

  // Backend distribution data
  const backendRequestData = backends
    ?.map((b) => ({ name: b.id, requests: b.totalRequests, failed: b.failedRequests }))
    .sort((a, b) => b.requests - a.requests) || [];

  const backendActiveData = backends
    ?.map((b) => ({ name: b.id, active: b.activeConnections }))
    .sort((a, b) => b.active - a.active) || [];

  // Status distribution
  const statusCounts = {};
  backends?.forEach((b) => {
    statusCounts[b.status] = (statusCounts[b.status] || 0) + 1;
  });
  const statusPieData = Object.entries(statusCounts).map(([name, value]) => ({ name, value }));

  return (
    <div>
      <PageHeader
        title="Metrics"
        description="Real-time traffic and backend metrics"
        actions={
          <a
            href="/actuator/prometheus"
            target="_blank"
            rel="noopener noreferrer"
            className="btn-secondary btn-sm"
          >
            <ExternalLink size={14} />
            Prometheus
          </a>
        }
      />

      {/* Global request rate chart */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Request Rate</h3>
          </div>
          <div className="card-body">
            <ResponsiveContainer width="100%" height={250}>
              <AreaChart data={historyRef.current}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
                <Tooltip contentStyle={{ fontSize: 12 }} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Area
                  type="monotone"
                  dataKey="requests"
                  stroke="#10b981"
                  fill="#d1fae5"
                  strokeWidth={2}
                  name="Requests"
                />
                <Area
                  type="monotone"
                  dataKey="failed"
                  stroke="#ef4444"
                  fill="#fee2e2"
                  strokeWidth={2}
                  name="Failed"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Active requests gauge */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Active Requests</h3>
          </div>
          <div className="card-body">
            <ResponsiveContainer width="100%" height={250}>
              <AreaChart data={historyRef.current}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
                <Tooltip contentStyle={{ fontSize: 12 }} />
                <Area
                  type="monotone"
                  dataKey="active"
                  stroke="#6366f1"
                  fill="#e0e7ff"
                  strokeWidth={2}
                  name="Active"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>
      </div>

      {/* Backend distribution */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mb-6">
        {/* Requests per backend */}
        <div className="card lg:col-span-2">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Requests per Backend</h3>
          </div>
          <div className="card-body">
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={backendRequestData} layout="vertical">
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis type="number" tick={{ fontSize: 10 }} />
                <YAxis dataKey="name" type="category" tick={{ fontSize: 10 }} width={100} />
                <Tooltip contentStyle={{ fontSize: 12 }} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="requests" fill="#6366f1" name="Total" radius={[0, 4, 4, 0]} />
                <Bar dataKey="failed" fill="#ef4444" name="Failed" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Health status pie chart */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Health Distribution</h3>
          </div>
          <div className="card-body flex items-center justify-center">
            {statusPieData.length > 0 ? (
              <ResponsiveContainer width="100%" height={250}>
                <PieChart>
                  <Pie
                    data={statusPieData}
                    cx="50%"
                    cy="50%"
                    innerRadius={50}
                    outerRadius={80}
                    paddingAngle={3}
                    dataKey="value"
                    label={({ name, value }) => `${name}: ${value}`}
                  >
                    {statusPieData.map((entry) => (
                      <Cell key={entry.name} fill={STATUS_COLORS[entry.name] || '#9ca3af'} />
                    ))}
                  </Pie>
                  <Tooltip contentStyle={{ fontSize: 12 }} />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <p className="text-sm text-gray-400">No backends</p>
            )}
          </div>
        </div>
      </div>

      {/* Active connections per backend */}
      <div className="card mb-6">
        <div className="card-header">
          <h3 className="text-sm font-semibold text-gray-900">Active Connections per Backend</h3>
        </div>
        <div className="card-body">
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={backendActiveData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="name" tick={{ fontSize: 10 }} />
              <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
              <Tooltip contentStyle={{ fontSize: 12 }} />
              <Bar dataKey="active" fill="#8b5cf6" name="Active Connections" radius={[4, 4, 0, 0]}>
                {backendActiveData.map((_, index) => (
                  <Cell key={index} fill={COLORS[index % COLORS.length]} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Health history */}
      <div className="card">
        <div className="card-header">
          <h3 className="text-sm font-semibold text-gray-900">Health & Circuit Breakers</h3>
        </div>
        <div className="card-body">
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={historyRef.current}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
              <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
              <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
              <Tooltip contentStyle={{ fontSize: 12 }} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Area
                type="stepAfter"
                dataKey="healthy"
                stroke="#10b981"
                fill="#d1fae5"
                strokeWidth={2}
                name="Healthy"
              />
              <Area
                type="stepAfter"
                dataKey="unhealthy"
                stroke="#ef4444"
                fill="#fee2e2"
                strokeWidth={2}
                name="Unhealthy"
              />
              <Area
                type="stepAfter"
                dataKey="circuits"
                stroke="#f59e0b"
                fill="#fef3c7"
                strokeWidth={2}
                name="Open Circuits"
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
}
