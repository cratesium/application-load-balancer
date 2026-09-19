import { usePolling } from '../hooks/usePolling';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import MetricCard from '../components/MetricCard';
import StatusBadge from '../components/StatusBadge';
import LoadingSpinner from '../components/LoadingSpinner';
import {
  Server,
  Activity,
  HeartPulse,
  AlertTriangle,
  Zap,
  Clock,
  ShieldCheck,
  GitBranch,
  RefreshCw,
  CheckCircle2,
  XCircle,
} from 'lucide-react';
import { Link } from 'react-router-dom';

function formatUptime(seconds) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const parts = [];
  if (d > 0) parts.push(`${d}d`);
  if (h > 0) parts.push(`${h}h`);
  if (m > 0) parts.push(`${m}m`);
  if (parts.length === 0) parts.push(`${s}s`);
  return parts.join(' ');
}

function formatNumber(num) {
  if (num >= 1_000_000) return `${(num / 1_000_000).toFixed(1)}M`;
  if (num >= 1_000) return `${(num / 1_000).toFixed(1)}K`;
  return String(num);
}

export default function Dashboard() {
  const { data: status, loading: statusLoading, refresh: refreshStatus } = usePolling(api.getStatus, 3000);
  const { data: backends, loading: backendsLoading } = usePolling(api.listBackends, 5000);

  if (statusLoading && !status) return <LoadingSpinner />;

  const healthyBackends = backends?.filter((b) => b.status === 'UP') || [];
  const unhealthyBackends = backends?.filter((b) => b.status !== 'UP') || [];
  const errorRate = status && status.totalRequests > 0
    ? ((status.failedRequests / status.totalRequests) * 100).toFixed(2)
    : '0.00';

  return (
    <div>
      <PageHeader
        title="Dashboard"
        description="Application Load Balancer overview"
        actions={
          <button onClick={refreshStatus} className="btn-secondary btn-sm">
            <RefreshCw size={14} />
            Refresh
          </button>
        }
      />

      {/* Accepting traffic banner */}
      {status && !status.acceptingTraffic && (
        <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-lg flex items-center gap-3">
          <XCircle size={20} className="text-red-600" />
          <div>
            <p className="text-sm font-medium text-red-800">Not accepting traffic</p>
            <p className="text-xs text-red-600">The load balancer is shutting down or not ready.</p>
          </div>
        </div>
      )}

      {status && status.acceptingTraffic && (
        <div className="mb-6 p-4 bg-emerald-50 border border-emerald-200 rounded-lg flex items-center gap-3">
          <CheckCircle2 size={20} className="text-emerald-600" />
          <div>
            <p className="text-sm font-medium text-emerald-800">Accepting traffic</p>
            <p className="text-xs text-emerald-600">
              Algorithm: <strong>{status.algorithm?.replace(/_/g, ' ')}</strong> &middot; Uptime: {formatUptime(status.uptimeSeconds)}
            </p>
          </div>
        </div>
      )}

      {/* Metrics Grid */}
      {status && (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
          <MetricCard
            icon={Server}
            label="Total Backends"
            value={status.totalBackends}
            subtext={`${status.healthyBackends} healthy`}
            color="blue"
          />
          <MetricCard
            icon={Activity}
            label="Active Requests"
            value={formatNumber(status.activeRequests)}
            color="purple"
          />
          <MetricCard
            icon={Zap}
            label="Total Requests"
            value={formatNumber(status.totalRequests)}
            subtext={`${formatNumber(status.failedRequests)} failed (${errorRate}%)`}
            color="green"
          />
          <MetricCard
            icon={AlertTriangle}
            label="Open Circuits"
            value={status.openCircuits}
            subtext={status.openCircuits > 0 ? 'Circuit breakers tripped' : 'All circuits closed'}
            color={status.openCircuits > 0 ? 'red' : 'green'}
          />
        </div>
      )}

      {/* Backend Health Table */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Backend list */}
        <div className="lg:col-span-2 card">
          <div className="card-header flex items-center justify-between">
            <h2 className="text-base font-semibold text-gray-900">Backend Health</h2>
            <Link to="/backends" className="text-sm text-aws-blue hover:underline">
              View all
            </Link>
          </div>
          {backendsLoading && !backends ? (
            <LoadingSpinner size="sm" />
          ) : (
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Backend</th>
                    <th>Status</th>
                    <th>Circuit</th>
                    <th>Active</th>
                    <th>Requests</th>
                  </tr>
                </thead>
                <tbody>
                  {backends?.map((b) => (
                    <tr key={b.id}>
                      <td>
                        <Link to={`/backends/${b.id}`} className="text-aws-blue hover:underline font-medium">
                          {b.id}
                        </Link>
                        <div className="text-xs text-gray-400">{b.url}</div>
                      </td>
                      <td>
                        <StatusBadge status={b.status} size="sm" />
                      </td>
                      <td>
                        <StatusBadge status={b.circuitState} size="sm" />
                      </td>
                      <td className="font-mono text-xs">{b.activeConnections}</td>
                      <td className="font-mono text-xs">{formatNumber(b.totalRequests)}</td>
                    </tr>
                  ))}
                  {(!backends || backends.length === 0) && (
                    <tr>
                      <td colSpan={5} className="text-center text-gray-400 py-8">
                        No backends registered
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Side panel */}
        <div className="space-y-6">
          {/* Algorithm card */}
          <div className="card card-body">
            <div className="flex items-center gap-3 mb-3">
              <div className="p-2 rounded-lg bg-purple-50 text-purple-600">
                <GitBranch size={20} strokeWidth={1.5} />
              </div>
              <div>
                <p className="text-xs text-gray-500">Active Algorithm</p>
                <p className="text-sm font-semibold text-gray-900">
                  {status?.algorithm?.replace(/_/g, ' ')}
                </p>
              </div>
            </div>
            <Link to="/algorithm" className="text-xs text-aws-blue hover:underline">
              Change algorithm
            </Link>
          </div>

          {/* Health summary card */}
          <div className="card card-body">
            <h3 className="text-sm font-semibold text-gray-900 mb-3 flex items-center gap-2">
              <HeartPulse size={16} />
              Health Summary
            </h3>
            <div className="space-y-2">
              <div className="flex items-center justify-between text-sm">
                <span className="text-gray-600">Healthy</span>
                <span className="font-mono font-medium text-emerald-600">{healthyBackends.length}</span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-gray-600">Unhealthy</span>
                <span className="font-mono font-medium text-red-600">{unhealthyBackends.length}</span>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-gray-600">503 Rejections</span>
                <span className="font-mono font-medium text-gray-900">
                  {formatNumber(status?.noHealthyBackendRejections || 0)}
                </span>
              </div>
            </div>
            {/* Health bar */}
            {backends && backends.length > 0 && (
              <div className="mt-3">
                <div className="flex rounded-full overflow-hidden h-2 bg-gray-200">
                  <div
                    className="bg-emerald-500 transition-all duration-500"
                    style={{ width: `${(healthyBackends.length / backends.length) * 100}%` }}
                  />
                  <div
                    className="bg-red-500 transition-all duration-500"
                    style={{
                      width: `${(unhealthyBackends.length / backends.length) * 100}%`,
                    }}
                  />
                </div>
              </div>
            )}
          </div>

          {/* Uptime card */}
          {status && (
            <div className="card card-body">
              <h3 className="text-sm font-semibold text-gray-900 mb-3 flex items-center gap-2">
                <Clock size={16} />
                Uptime
              </h3>
              <p className="text-2xl font-semibold text-gray-900">{formatUptime(status.uptimeSeconds)}</p>
              <p className="text-xs text-gray-500 mt-1">
                Started {new Date(status.startedAt).toLocaleString()}
              </p>
            </div>
          )}

          {/* Security card */}
          <div className="card card-body">
            <h3 className="text-sm font-semibold text-gray-900 mb-3 flex items-center gap-2">
              <ShieldCheck size={16} />
              Security
            </h3>
            <div className="space-y-2 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-gray-600">Admin API</span>
                <StatusBadge status="UP" size="sm" />
              </div>
              <div className="flex items-center justify-between">
                <span className="text-gray-600">Auth</span>
                <span className="text-xs font-medium text-gray-700">Bearer Token</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
