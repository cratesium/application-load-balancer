import { useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import PageHeader from '../components/PageHeader';
import { RefreshCw, RotateCcw, Check, Info, ExternalLink } from 'lucide-react';

export default function SettingsPage() {
  const { logout } = useAuth();
  const [reloading, setReloading] = useState(false);
  const [reloadResult, setReloadResult] = useState(null);

  async function handleReload() {
    setReloading(true);
    setReloadResult(null);
    try {
      const result = await api.reloadConfig();
      setReloadResult({ type: 'success', data: result });
    } catch (err) {
      setReloadResult({
        type: 'error',
        message: err.body?.message || err.message,
      });
    } finally {
      setReloading(false);
    }
  }

  return (
    <div>
      <PageHeader title="Settings" description="Configuration and administration" />

      <div className="space-y-6 max-w-2xl">
        {/* Config Reload */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Configuration</h3>
          </div>
          <div className="card-body space-y-4">
            <p className="text-sm text-gray-600">
              Reload the configuration file without restarting the load balancer. This applies changes to
              the algorithm, backend list, weights, and routes.
            </p>

            {reloadResult && (
              <div
                className={`p-4 rounded-lg ${
                  reloadResult.type === 'success'
                    ? 'bg-emerald-50 border border-emerald-200'
                    : 'bg-red-50 border border-red-200'
                }`}
              >
                {reloadResult.type === 'success' ? (
                  <div className="text-sm text-emerald-800">
                    <p className="font-medium flex items-center gap-2">
                      <Check size={16} /> Configuration reloaded successfully
                    </p>
                    {reloadResult.data.algorithm && (
                      <p className="mt-1">Algorithm: {reloadResult.data.algorithm}</p>
                    )}
                    {reloadResult.data.addedBackends?.length > 0 && (
                      <p className="mt-1">
                        Added: {reloadResult.data.addedBackends.join(', ')}
                      </p>
                    )}
                    {reloadResult.data.removedBackends?.length > 0 && (
                      <p className="mt-1">
                        Removed: {reloadResult.data.removedBackends.join(', ')}
                      </p>
                    )}
                    {reloadResult.data.unsupported?.length > 0 && (
                      <p className="mt-1 text-amber-700">
                        Unsupported (require restart): {reloadResult.data.unsupported.join(', ')}
                      </p>
                    )}
                  </div>
                ) : (
                  <p className="text-sm text-red-800">{reloadResult.message}</p>
                )}
              </div>
            )}

            <button onClick={handleReload} disabled={reloading} className="btn-primary btn-sm">
              {reloading ? (
                <>
                  <RefreshCw size={14} className="animate-spin" /> Reloading...
                </>
              ) : (
                <>
                  <RotateCcw size={14} /> Reload Configuration
                </>
              )}
            </button>

            <div className="p-3 bg-amber-50 border border-amber-200 rounded text-sm text-amber-800">
              <div className="flex gap-2">
                <Info size={16} className="flex-shrink-0 mt-0.5" />
                <div>
                  <p className="font-medium">Not all settings are reloadable</p>
                  <p className="mt-0.5 text-xs">
                    Timeouts, connection pool sizing, resource limits, admin token, and health check
                    thresholds require a full restart to take effect.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Session */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">Session</h3>
          </div>
          <div className="card-body space-y-4">
            <p className="text-sm text-gray-600">
              Your admin token is stored in session storage and will be cleared when you close this tab.
            </p>
            <button onClick={logout} className="btn-danger btn-sm">
              Sign Out
            </button>
          </div>
        </div>

        {/* Endpoints reference */}
        <div className="card">
          <div className="card-header">
            <h3 className="text-sm font-semibold text-gray-900">API Endpoints</h3>
          </div>
          <div className="card-body">
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Method</th>
                    <th>Path</th>
                    <th>Description</th>
                  </tr>
                </thead>
                <tbody className="text-xs">
                  <tr>
                    <td><MethodBadge method="GET" /></td>
                    <td className="font-mono">/admin/status</td>
                    <td>ALB status overview</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="GET" /></td>
                    <td className="font-mono">/admin/backends</td>
                    <td>List all backends</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="POST" /></td>
                    <td className="font-mono">/admin/backends</td>
                    <td>Register new backend</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="DELETE" /></td>
                    <td className="font-mono">/admin/backends/:id</td>
                    <td>Drain &amp; remove</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="POST" /></td>
                    <td className="font-mono">/admin/backends/:id/disable</td>
                    <td>Disable backend</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="POST" /></td>
                    <td className="font-mono">/admin/backends/:id/enable</td>
                    <td>Enable backend</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="PUT" /></td>
                    <td className="font-mono">/admin/backends/:id/weight</td>
                    <td>Update weight</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="GET" /></td>
                    <td className="font-mono">/admin/algorithm</td>
                    <td>Current algorithm</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="POST" /></td>
                    <td className="font-mono">/admin/load-balancer/algorithm</td>
                    <td>Switch algorithm</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="GET" /></td>
                    <td className="font-mono">/admin/routes</td>
                    <td>List routes</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="POST" /></td>
                    <td className="font-mono">/admin/config/reload</td>
                    <td>Hot reload config</td>
                  </tr>
                  <tr>
                    <td><MethodBadge method="GET" /></td>
                    <td className="font-mono">/actuator/prometheus</td>
                    <td>Prometheus metrics</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function MethodBadge({ method }) {
  const colors = {
    GET: 'bg-emerald-50 text-emerald-700',
    POST: 'bg-blue-50 text-blue-700',
    PUT: 'bg-amber-50 text-amber-700',
    DELETE: 'bg-red-50 text-red-700',
  };
  return (
    <span className={`text-xs font-mono font-semibold px-1.5 py-0.5 rounded ${colors[method] || 'bg-gray-100 text-gray-600'}`}>
      {method}
    </span>
  );
}
