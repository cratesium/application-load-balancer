import { useState } from 'react';
import { Link } from 'react-router-dom';
import { usePolling } from '../hooks/usePolling';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingSpinner from '../components/LoadingSpinner';
import EmptyState from '../components/EmptyState';
import {
  Plus,
  RefreshCw,
  Server,
  Trash2,
  Power,
  PowerOff,
  Weight,
  RotateCcw,
  ChevronRight,
  ExternalLink,
} from 'lucide-react';

function AddBackendModal({ open, onClose, onAdded }) {
  const [form, setForm] = useState({ id: '', host: '', port: 8080, weight: 1, secure: false, enabled: true });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  if (!open) return null;

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError('');
    try {
      await api.addBackend({ ...form, port: Number(form.port), weight: Number(form.weight) });
      onAdded();
      onClose();
      setForm({ id: '', host: '', port: 8080, weight: 1, secure: false, enabled: true });
    } catch (err) {
      setError(err.body?.message || err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="card w-full max-w-lg mx-4 shadow-xl">
        <div className="card-header">
          <h3 className="text-base font-semibold text-gray-900">Register New Backend</h3>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="card-body space-y-4">
            {error && (
              <div className="p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">{error}</div>
            )}
            <div className="grid grid-cols-2 gap-4">
              <div className="col-span-2">
                <label className="label">Backend ID</label>
                <input
                  className="input-field"
                  placeholder="e.g. backend-4"
                  value={form.id}
                  onChange={(e) => setForm({ ...form, id: e.target.value })}
                  required
                />
              </div>
              <div>
                <label className="label">Host</label>
                <input
                  className="input-field"
                  placeholder="e.g. 10.0.1.5"
                  value={form.host}
                  onChange={(e) => setForm({ ...form, host: e.target.value })}
                  required
                />
              </div>
              <div>
                <label className="label">Port</label>
                <input
                  type="number"
                  className="input-field"
                  min={1}
                  max={65535}
                  value={form.port}
                  onChange={(e) => setForm({ ...form, port: e.target.value })}
                  required
                />
              </div>
              <div>
                <label className="label">Weight</label>
                <input
                  type="number"
                  className="input-field"
                  min={1}
                  value={form.weight}
                  onChange={(e) => setForm({ ...form, weight: e.target.value })}
                  required
                />
              </div>
              <div className="flex items-end gap-6">
                <label className="flex items-center gap-2 text-sm cursor-pointer">
                  <input
                    type="checkbox"
                    checked={form.secure}
                    onChange={(e) => setForm({ ...form, secure: e.target.checked })}
                    className="rounded border-gray-300"
                  />
                  HTTPS
                </label>
                <label className="flex items-center gap-2 text-sm cursor-pointer">
                  <input
                    type="checkbox"
                    checked={form.enabled}
                    onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                    className="rounded border-gray-300"
                  />
                  Enabled
                </label>
              </div>
            </div>
          </div>
          <div className="px-6 py-3 bg-gray-50 rounded-b-lg flex justify-end gap-3">
            <button type="button" onClick={onClose} className="btn-secondary btn-sm">
              Cancel
            </button>
            <button type="submit" disabled={loading} className="btn-primary btn-sm">
              {loading ? 'Registering...' : 'Register Backend'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function WeightModal({ open, backend, onClose, onUpdated }) {
  const [weight, setWeight] = useState(backend?.weight || 1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!open || !backend) return null;

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError('');
    try {
      await api.updateWeight(backend.id, Number(weight));
      onUpdated();
      onClose();
    } catch (err) {
      setError(err.body?.message || err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="card w-full max-w-sm mx-4 shadow-xl">
        <div className="card-header">
          <h3 className="text-base font-semibold text-gray-900">
            Update Weight &mdash; {backend.id}
          </h3>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="card-body space-y-4">
            {error && (
              <div className="p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">{error}</div>
            )}
            <div>
              <label className="label">Weight</label>
              <input
                type="number"
                className="input-field"
                min={1}
                value={weight}
                onChange={(e) => setWeight(e.target.value)}
                required
                autoFocus
              />
              <p className="text-xs text-gray-500 mt-1">
                Current: {backend.weight}. Higher weight = more traffic in weighted algorithms.
              </p>
            </div>
          </div>
          <div className="px-6 py-3 bg-gray-50 rounded-b-lg flex justify-end gap-3">
            <button type="button" onClick={onClose} className="btn-secondary btn-sm">
              Cancel
            </button>
            <button type="submit" disabled={loading} className="btn-primary btn-sm">
              {loading ? 'Updating...' : 'Update Weight'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function Backends() {
  const { data: backends, loading, refresh } = usePolling(api.listBackends, 5000);
  const [showAdd, setShowAdd] = useState(false);
  const [weightTarget, setWeightTarget] = useState(null);
  const [confirm, setConfirm] = useState(null);
  const [actionLoading, setActionLoading] = useState('');

  async function handleAction(action, id) {
    setActionLoading(id);
    try {
      await action(id);
      refresh();
    } catch {
      // Error handled by polling refresh
    } finally {
      setActionLoading('');
      setConfirm(null);
    }
  }

  if (loading && !backends) return <LoadingSpinner />;

  const healthy = backends?.filter((b) => b.status === 'UP').length || 0;
  const total = backends?.length || 0;

  return (
    <div>
      <PageHeader
        title="Target Groups"
        description={`${total} registered backend${total !== 1 ? 's' : ''}, ${healthy} healthy`}
        actions={
          <>
            <button onClick={refresh} className="btn-secondary btn-sm">
              <RefreshCw size={14} />
              Refresh
            </button>
            <button onClick={() => setShowAdd(true)} className="btn-primary btn-sm">
              <Plus size={14} />
              Register Backend
            </button>
          </>
        }
      />

      {backends && backends.length > 0 ? (
        <div className="card">
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Backend ID</th>
                  <th>URL</th>
                  <th>Status</th>
                  <th>Circuit</th>
                  <th>Weight</th>
                  <th>Active Conn.</th>
                  <th>Total Req.</th>
                  <th>Failed</th>
                  <th>Success Rate</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {backends.map((b) => {
                  const successRate =
                    b.totalRequests > 0
                      ? ((b.successfulRequests / b.totalRequests) * 100).toFixed(1)
                      : '---';
                  return (
                    <tr key={b.id}>
                      <td>
                        <Link
                          to={`/backends/${b.id}`}
                          className="text-aws-blue hover:underline font-medium inline-flex items-center gap-1"
                        >
                          {b.id}
                          <ChevronRight size={14} />
                        </Link>
                      </td>
                      <td>
                        <span className="font-mono text-xs text-gray-500">{b.url}</span>
                      </td>
                      <td>
                        <StatusBadge status={b.status} size="sm" />
                      </td>
                      <td>
                        <StatusBadge status={b.circuitState} size="sm" />
                      </td>
                      <td>
                        <button
                          onClick={() => setWeightTarget(b)}
                          className="inline-flex items-center gap-1 text-sm font-mono hover:text-aws-blue transition-colors"
                          title="Click to change weight"
                        >
                          {b.weight}
                          <Weight size={12} className="text-gray-400" />
                        </button>
                      </td>
                      <td className="font-mono text-xs">{b.activeConnections}</td>
                      <td className="font-mono text-xs">{b.totalRequests.toLocaleString()}</td>
                      <td className="font-mono text-xs text-red-600">{b.failedRequests.toLocaleString()}</td>
                      <td>
                        <span
                          className={`font-mono text-xs font-medium ${
                            successRate === '---'
                              ? 'text-gray-400'
                              : Number(successRate) >= 99
                                ? 'text-emerald-600'
                                : Number(successRate) >= 95
                                  ? 'text-amber-600'
                                  : 'text-red-600'
                          }`}
                        >
                          {successRate === '---' ? successRate : `${successRate}%`}
                        </span>
                      </td>
                      <td>
                        <div className="flex items-center gap-1">
                          {b.status === 'DISABLED' || b.status === 'DOWN' ? (
                            <button
                              onClick={() => handleAction(api.enableBackend, b.id)}
                              disabled={actionLoading === b.id}
                              className="p-1.5 rounded hover:bg-emerald-50 text-emerald-600 transition-colors"
                              title="Enable"
                            >
                              <Power size={14} />
                            </button>
                          ) : (
                            <button
                              onClick={() =>
                                setConfirm({
                                  title: 'Disable Backend',
                                  message: `Disable "${b.id}"? It will be drained and stop receiving new requests. ${b.activeConnections} in-flight request(s) will complete.`,
                                  action: () => handleAction(api.disableBackend, b.id),
                                })
                              }
                              disabled={actionLoading === b.id}
                              className="p-1.5 rounded hover:bg-amber-50 text-amber-600 transition-colors"
                              title="Disable"
                            >
                              <PowerOff size={14} />
                            </button>
                          )}
                          {b.circuitState === 'OPEN' && (
                            <button
                              onClick={() => handleAction(api.resetCircuitBreaker, b.id)}
                              disabled={actionLoading === b.id}
                              className="p-1.5 rounded hover:bg-blue-50 text-blue-600 transition-colors"
                              title="Reset Circuit Breaker"
                            >
                              <RotateCcw size={14} />
                            </button>
                          )}
                          <button
                            onClick={() =>
                              setConfirm({
                                title: 'Deregister Backend',
                                message: `Remove "${b.id}" from the pool? It will be drained first — ${b.activeConnections} in-flight request(s) will complete before removal.`,
                                action: () => handleAction(api.removeBackend, b.id),
                              })
                            }
                            disabled={actionLoading === b.id}
                            className="p-1.5 rounded hover:bg-red-50 text-red-500 transition-colors"
                            title="Deregister"
                          >
                            <Trash2 size={14} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        <div className="card card-body">
          <EmptyState
            icon={Server}
            title="No backends registered"
            description="Register a backend to start routing traffic."
            action={
              <button onClick={() => setShowAdd(true)} className="btn-primary btn-sm">
                <Plus size={14} /> Register Backend
              </button>
            }
          />
        </div>
      )}

      <AddBackendModal open={showAdd} onClose={() => setShowAdd(false)} onAdded={refresh} />
      <WeightModal
        open={!!weightTarget}
        backend={weightTarget}
        onClose={() => setWeightTarget(null)}
        onUpdated={refresh}
      />
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
