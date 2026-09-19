import { useState, useEffect, useRef } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { usePolling } from '../hooks/usePolling';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingSpinner from '../components/LoadingSpinner';
import {
  ArrowLeft,
  RefreshCw,
  Power,
  PowerOff,
  Trash2,
  RotateCcw,
  Server,
  Activity,
  CheckCircle2,
  XCircle,
  Shield,
  Clock,
  Weight,
  Globe,
} from 'lucide-react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';

function formatTime(instant) {
  if (!instant) return 'Never';
  return new Date(instant).toLocaleString();
}

function formatRelative(instant) {
  if (!instant) return 'N/A';
  const diff = Date.now() - new Date(instant).getTime();
  const secs = Math.floor(diff / 1000);
  if (secs < 60) return `${secs}s ago`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ${mins % 60}m ago`;
}

export default function BackendDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: backend, loading, refresh } = usePolling(() => api.getBackend(id), 3000);
  const [confirm, setConfirm] = useState(null);
  const [actionLoading, setActionLoading] = useState(false);

  // Track request history for the chart
  const historyRef = useRef([]);
  const prevRef = useRef(null);

  useEffect(() => {
    if (!backend) return;
    const now = new Date();
    const timeLabel = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    const prevTotal = prevRef.current?.totalRequests || backend.totalRequests;
    const prevFailed = prevRef.current?.failedRequests || backend.failedRequests;
    const rps = Math.max(0, backend.totalRequests - prevTotal);
    const failedDelta = Math.max(0, backend.failedRequests - prevFailed);

    historyRef.current = [
      ...historyRef.current.slice(-59),
      {
        time: timeLabel,
        requests: rps,
        failed: failedDelta,
        active: backend.activeConnections,
      },
    ];
    prevRef.current = backend;
  }, [backend]);

  async function handleAction(action) {
    setActionLoading(true);
    try {
      await action(id);
      refresh();
    } catch {
      // Handled by refresh
    } finally {
      setActionLoading(false);
      setConfirm(null);
    }
  }

  if (loading && !backend) return <LoadingSpinner />;

  if (!backend) {
    return (
      <div className="text-center py-20">
        <Server size={48} className="mx-auto text-gray-300 mb-4" />
        <h2 className="text-lg font-medium text-gray-700">Backend not found</h2>
        <p className="text-sm text-gray-500 mt-1">The backend "{id}" does not exist.</p>
        <Link to="/backends" className="btn-primary btn-sm mt-4 inline-flex">
          <ArrowLeft size={14} /> Back to Target Groups
        </Link>
      </div>
    );
  }

  const successRate =
    backend.totalRequests > 0
      ? ((backend.successfulRequests / backend.totalRequests) * 100).toFixed(2)
      : '---';

  const isActionable = backend.status !== 'DRAINING';

  return (
    <div>
      {/* Breadcrumb */}
      <div className="mb-4">
        <Link
          to="/backends"
          className="inline-flex items-center gap-1 text-sm text-gray-500 hover:text-aws-blue transition-colors"
        >
          <ArrowLeft size={14} />
          Target Groups
        </Link>
      </div>

      <PageHeader
        title={backend.id}
        description={backend.url}
        actions={
          <div className="flex items-center gap-2">
            <button onClick={refresh} className="btn-secondary btn-sm">
              <RefreshCw size={14} />
            </button>
            {backend.status === 'DISABLED' ? (
              <button
                onClick={() => handleAction(api.enableBackend)}
                disabled={actionLoading}
                className="btn-primary btn-sm"
              >
                <Power size={14} /> Enable
              </button>
            ) : isActionable ? (
              <button
                onClick={() =>
                  setConfirm({
                    title: 'Disable Backend',
                    message: `This will drain "${backend.id}" and stop it from receiving new requests.`,
                    action: () => handleAction(api.disableBackend),
                  })
                }
                disabled={actionLoading}
                className="btn-warning btn-sm"
              >
                <PowerOff size={14} /> Disable
              </button>
            ) : null}
            <button
              onClick={() =>
                setConfirm({
                  title: 'Deregister Backend',
                  message: `This will remove "${backend.id}" from the pool permanently.`,
                  action: () => {
                    handleAction(api.removeBackend).then(() => navigate('/backends'));
                  },
                })
              }
              disabled={actionLoading}
              className="btn-danger btn-sm"
            >
              <Trash2 size={14} /> Deregister
            </button>
          </div>
        }
      />

      {/* Status Cards Row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        <div className="card card-body">
          <p className="text-xs text-gray-500 mb-1">Health Status</p>
          <StatusBadge status={backend.status} />
        </div>
        <div className="card card-body">
          <p className="text-xs text-gray-500 mb-1">Circuit Breaker</p>
          <div className="flex items-center gap-2">
            <StatusBadge status={backend.circuitState} />
            {backend.circuitState === 'OPEN' && (
              <button
                onClick={() => handleAction(api.resetCircuitBreaker)}
                disabled={actionLoading}
                className="p-1 rounded hover:bg-blue-50 text-blue-600"
                title="Reset"
              >
                <RotateCcw size={14} />
              </button>
            )}
          </div>
        </div>
        <div className="card card-body">
          <p className="text-xs text-gray-500 mb-1">Active Connections</p>
          <p className="text-2xl font-semibold text-gray-900">{backend.activeConnections}</p>
        </div>
        <div className="card card-body">
          <p className="text-xs text-gray-500 mb-1">Weight</p>
          <p className="text-2xl font-semibold text-gray-900">{backend.weight}</p>
        </div>
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        {/* Active connections chart */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Active Connections</h3>
          </div>
          <div className="card-body">
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={historyRef.current}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
                <Tooltip contentStyle={{ fontSize: 12 }} />
                <Area
                  type="monotone"
                  dataKey="active"
                  stroke="#8b5cf6"
                  fill="#ede9fe"
                  strokeWidth={2}
                  name="Active"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Request rate chart */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Request Rate (per poll interval)</h3>
          </div>
          <div className="card-body">
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart data={historyRef.current}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 10 }} allowDecimals={false} />
                <Tooltip contentStyle={{ fontSize: 12 }} />
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
      </div>

      {/* Details Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Connection details */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
              <Globe size={16} />
              Connection Details
            </h3>
          </div>
          <div className="card-body">
            <dl className="divide-y divide-gray-100">
              <DetailRow label="Host" value={backend.host} />
              <DetailRow label="Port" value={backend.port} />
              <DetailRow label="Protocol" value={backend.secure ? 'HTTPS' : 'HTTP'} />
              <DetailRow label="URL" value={backend.url} mono />
              <DetailRow label="Weight" value={backend.weight} />
            </dl>
          </div>
        </div>

        {/* Request statistics */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
              <Activity size={16} />
              Request Statistics
            </h3>
          </div>
          <div className="card-body">
            <dl className="divide-y divide-gray-100">
              <DetailRow label="Total Requests" value={backend.totalRequests.toLocaleString()} />
              <DetailRow
                label="Successful"
                value={backend.successfulRequests.toLocaleString()}
                valueClass="text-emerald-600"
              />
              <DetailRow
                label="Failed"
                value={backend.failedRequests.toLocaleString()}
                valueClass="text-red-600"
              />
              <DetailRow
                label="Success Rate"
                value={successRate === '---' ? successRate : `${successRate}%`}
                valueClass={
                  successRate === '---'
                    ? 'text-gray-400'
                    : Number(successRate) >= 99
                      ? 'text-emerald-600'
                      : 'text-red-600'
                }
              />
              <DetailRow label="Active Connections" value={backend.activeConnections} />
            </dl>
          </div>
        </div>

        {/* Health check info */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
              <Shield size={16} />
              Health & Circuit Breaker
            </h3>
          </div>
          <div className="card-body">
            <dl className="divide-y divide-gray-100">
              <DetailRow
                label="Health Status"
                value={<StatusBadge status={backend.status} size="sm" />}
              />
              <DetailRow
                label="Circuit State"
                value={<StatusBadge status={backend.circuitState} size="sm" />}
              />
              <DetailRow label="Last Health Check" value={formatRelative(backend.lastHealthCheck)} />
              <DetailRow label="Last Failure" value={formatRelative(backend.lastFailure)} />
              <DetailRow label="Last State Change" value={formatTime(backend.lastStateChange)} />
              {backend.lastFailureReason && (
                <DetailRow
                  label="Last Failure Reason"
                  value={backend.lastFailureReason}
                  valueClass="text-red-600"
                />
              )}
            </dl>
          </div>
        </div>

        {/* Timestamps */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
              <Clock size={16} />
              Timeline
            </h3>
          </div>
          <div className="card-body">
            <div className="space-y-4">
              <TimelineEvent
                icon={CheckCircle2}
                color="emerald"
                label="Last Health Check"
                time={backend.lastHealthCheck}
              />
              <TimelineEvent
                icon={XCircle}
                color="red"
                label="Last Failure"
                time={backend.lastFailure}
                detail={backend.lastFailureReason}
              />
              <TimelineEvent
                icon={Activity}
                color="blue"
                label="Last State Change"
                time={backend.lastStateChange}
                detail={`Transitioned to ${backend.status}`}
              />
            </div>
          </div>
        </div>
      </div>

      <ConfirmDialog
        open={!!confirm}
        title={confirm?.title}
        message={confirm?.message}
        confirmLabel={confirm?.title}
        onConfirm={confirm?.action}
        onCancel={() => setConfirm(null)}
      />
    </div>
  );
}

function DetailRow({ label, value, mono, valueClass }) {
  return (
    <div className="flex justify-between py-2.5">
      <dt className="text-sm text-gray-500">{label}</dt>
      <dd className={`text-sm font-medium ${mono ? 'font-mono' : ''} ${valueClass || 'text-gray-900'}`}>
        {value}
      </dd>
    </div>
  );
}

function TimelineEvent({ icon: Icon, color, label, time, detail }) {
  const colorMap = {
    emerald: 'bg-emerald-100 text-emerald-600',
    red: 'bg-red-100 text-red-600',
    blue: 'bg-blue-100 text-blue-600',
    amber: 'bg-amber-100 text-amber-600',
  };

  return (
    <div className="flex gap-3">
      <div className={`p-1.5 rounded-full flex-shrink-0 ${colorMap[color]}`}>
        <Icon size={14} />
      </div>
      <div className="min-w-0">
        <p className="text-sm font-medium text-gray-900">{label}</p>
        <p className="text-xs text-gray-500">{time ? formatTime(time) : 'Never'}</p>
        {detail && <p className="text-xs text-gray-400 mt-0.5">{detail}</p>}
      </div>
    </div>
  );
}
