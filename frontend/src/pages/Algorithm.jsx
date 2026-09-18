import { useState } from 'react';
import { usePolling } from '../hooks/usePolling';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import LoadingSpinner from '../components/LoadingSpinner';
import { RefreshCw, Check, GitBranch, ArrowRight, Info } from 'lucide-react';

const algorithmInfo = {
  ROUND_ROBIN: {
    description: 'Distributes requests evenly across all healthy backends in rotation.',
    best: 'Uniform backends with similar capacity',
    icon: '🔄',
  },
  WEIGHTED_ROUND_ROBIN: {
    description: 'Distributes requests proportionally to each backend\'s weight using smooth scheduling.',
    best: 'Backends with different capacities',
    icon: '⚖️',
  },
  RANDOM: {
    description: 'Selects a random healthy backend for each request.',
    best: 'Simple distribution with many backends',
    icon: '🎲',
  },
  LEAST_CONNECTIONS: {
    description: 'Routes to the backend with the fewest in-flight requests.',
    best: 'Requests with variable processing time',
    icon: '📊',
  },
  WEIGHTED_LEAST_CONNECTIONS: {
    description: 'Routes to the backend with the lowest connection-to-weight ratio.',
    best: 'Mixed capacity backends with variable request times',
    icon: '📈',
  },
  IP_HASH: {
    description: 'Hashes the client IP to always route the same client to the same backend.',
    best: 'Session affinity without cookies',
    icon: '🔗',
  },
  CONSISTENT_HASH: {
    description: 'Uses a hash ring for minimal request remapping when backends are added or removed.',
    best: 'Caching layers, stateful backends',
    icon: '💍',
  },
};

export default function AlgorithmPage() {
  const { data: algorithmData, loading, refresh } = usePolling(api.getAlgorithm, 10000);
  const [switching, setSwitching] = useState(false);
  const [result, setResult] = useState(null);

  async function handleSwitch(algorithm) {
    if (algorithm === algorithmData?.algorithm) return;
    setSwitching(true);
    setResult(null);
    try {
      const res = await api.switchAlgorithm(algorithm);
      setResult({
        type: 'success',
        message: `Switched from ${res.previousAlgorithm} to ${res.currentAlgorithm}`,
      });
      refresh();
    } catch (err) {
      setResult({
        type: 'error',
        message: err.body?.message || err.message,
      });
    } finally {
      setSwitching(false);
    }
  }

  if (loading && !algorithmData) return <LoadingSpinner />;

  return (
    <div>
      <PageHeader
        title="Load Balancing Algorithm"
        description="Select the algorithm used to distribute traffic across backends"
        actions={
          <button onClick={refresh} className="btn-secondary btn-sm">
            <RefreshCw size={14} />
            Refresh
          </button>
        }
      />

      {/* Result banner */}
      {result && (
        <div
          className={`mb-6 p-4 rounded-lg flex items-center gap-3 ${
            result.type === 'success'
              ? 'bg-emerald-50 border border-emerald-200'
              : 'bg-red-50 border border-red-200'
          }`}
        >
          {result.type === 'success' ? (
            <Check size={18} className="text-emerald-600" />
          ) : (
            <Info size={18} className="text-red-600" />
          )}
          <p
            className={`text-sm font-medium ${
              result.type === 'success' ? 'text-emerald-800' : 'text-red-800'
            }`}
          >
            {result.message}
          </p>
        </div>
      )}

      {/* Current algorithm highlight */}
      <div className="card card-body mb-6">
        <div className="flex items-center gap-4">
          <div className="p-3 rounded-lg bg-purple-50">
            <GitBranch size={24} className="text-purple-600" />
          </div>
          <div>
            <p className="text-xs text-gray-500 uppercase tracking-wide font-medium">Active Algorithm</p>
            <p className="text-xl font-semibold text-gray-900">
              {algorithmData?.algorithm?.replace(/_/g, ' ')}
            </p>
          </div>
        </div>
      </div>

      {/* Algorithm cards grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {algorithmData?.supported?.map((algo) => {
          const info = algorithmInfo[algo] || {};
          const isActive = algo === algorithmData.algorithm;
          return (
            <div
              key={algo}
              className={`card transition-all ${
                isActive ? 'ring-2 ring-aws-blue border-aws-blue' : 'hover:border-gray-300'
              }`}
            >
              <div className="card-body">
                <div className="flex items-start justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <span className="text-xl">{info.icon || '🔧'}</span>
                    <h3 className="text-sm font-semibold text-gray-900">{algo.replace(/_/g, ' ')}</h3>
                  </div>
                  {isActive && (
                    <span className="inline-flex items-center gap-1 text-xs font-medium text-aws-blue bg-blue-50 px-2 py-0.5 rounded-full">
                      <Check size={12} /> Active
                    </span>
                  )}
                </div>
                <p className="text-sm text-gray-600 mb-2">{info.description || 'No description available.'}</p>
                {info.best && (
                  <p className="text-xs text-gray-500">
                    <strong>Best for:</strong> {info.best}
                  </p>
                )}
                <div className="mt-4">
                  {isActive ? (
                    <span className="text-xs text-gray-400 italic">Currently active</span>
                  ) : (
                    <button
                      onClick={() => handleSwitch(algo)}
                      disabled={switching}
                      className="btn-secondary btn-sm w-full justify-center"
                    >
                      {switching ? (
                        'Switching...'
                      ) : (
                        <>
                          Switch to this <ArrowRight size={12} />
                        </>
                      )}
                    </button>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Info box */}
      <div className="mt-6 card card-body bg-blue-50 border-blue-200">
        <div className="flex gap-3">
          <Info size={18} className="text-blue-600 flex-shrink-0 mt-0.5" />
          <div className="text-sm text-blue-800">
            <p className="font-medium mb-1">Hot Swap</p>
            <p>
              Algorithm changes take effect immediately on the next request. In-flight requests continue
              with their already-selected backend. No restart or drain is needed.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
