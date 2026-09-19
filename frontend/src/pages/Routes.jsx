import { usePolling } from '../hooks/usePolling';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import LoadingSpinner from '../components/LoadingSpinner';
import EmptyState from '../components/EmptyState';
import { RefreshCw, Route, Info, ArrowRight } from 'lucide-react';

export default function RoutesPage() {
  const { data: routes, loading, refresh } = usePolling(api.listRoutes, 10000);

  if (loading && !routes) return <LoadingSpinner />;

  return (
    <div>
      <PageHeader
        title="Routes"
        description="Path-based routing rules for directing traffic to specific backend pools"
        actions={
          <button onClick={refresh} className="btn-secondary btn-sm">
            <RefreshCw size={14} />
            Refresh
          </button>
        }
      />

      {routes && routes.length > 0 ? (
        <div className="space-y-4">
          {routes.map((route, index) => (
            <div key={route.id || index} className="card">
              <div className="card-body">
                <div className="flex items-start justify-between">
                  <div>
                    <div className="flex items-center gap-3 mb-2">
                      <span className="text-xs font-mono bg-gray-100 px-2 py-0.5 rounded text-gray-600">
                        #{index + 1}
                      </span>
                      <h3 className="text-sm font-semibold text-gray-900">{route.id}</h3>
                    </div>

                    <div className="flex items-center gap-2 mb-3">
                      <code className="text-sm bg-blue-50 text-blue-800 px-2 py-0.5 rounded font-mono">
                        {route.path}
                      </code>
                      {route.methods && route.methods.length > 0 && (
                        <div className="flex gap-1">
                          {route.methods.map((method) => (
                            <span
                              key={method}
                              className="text-xs font-mono font-medium bg-amber-50 text-amber-800 px-1.5 py-0.5 rounded"
                            >
                              {method}
                            </span>
                          ))}
                        </div>
                      )}
                      {(!route.methods || route.methods.length === 0) && (
                        <span className="text-xs text-gray-400">All methods</span>
                      )}
                    </div>

                    <div className="flex items-center gap-2 text-sm text-gray-600">
                      <ArrowRight size={14} className="text-gray-400" />
                      <span>Backends:</span>
                      <div className="flex gap-1">
                        {route.backends?.map((bid) => (
                          <span
                            key={bid}
                            className="text-xs font-mono bg-gray-100 text-gray-700 px-2 py-0.5 rounded"
                          >
                            {bid}
                          </span>
                        ))}
                      </div>
                    </div>
                  </div>

                  {route.algorithm && (
                    <span className="text-xs bg-purple-50 text-purple-700 px-2 py-1 rounded font-medium">
                      {route.algorithm.replace(/_/g, ' ')}
                    </span>
                  )}
                </div>
              </div>
            </div>
          ))}

          <div className="card card-body bg-blue-50 border-blue-200">
            <div className="flex gap-3">
              <Info size={18} className="text-blue-600 flex-shrink-0 mt-0.5" />
              <div className="text-sm text-blue-800">
                <p className="font-medium mb-1">Route Evaluation</p>
                <p>
                  Routes are evaluated in order — first match wins. Unmatched requests use the global
                  backend pool. A matched route whose backends are all unavailable returns 503
                  rather than falling back to the global pool.
                </p>
              </div>
            </div>
          </div>
        </div>
      ) : (
        <div className="card card-body">
          <EmptyState
            icon={Route}
            title="No routes configured"
            description="All requests are routed to the global backend pool. Add routes in application.yml to enable path-based routing."
          />
          <div className="mt-6 mx-auto max-w-lg">
            <p className="text-xs text-gray-500 mb-2 font-medium">Example configuration:</p>
            <pre className="text-xs bg-gray-900 text-gray-300 p-4 rounded-lg overflow-x-auto">
{`load-balancer:
  routes:
    - id: users-api
      path: /api/users/**
      backends: [backend-1, backend-2]
    - id: orders-writes
      path: /api/orders/**
      methods: [POST, PUT, DELETE]
      backends: [backend-3]
      algorithm: LEAST_CONNECTIONS`}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}
