import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { api } from '../api/client';
import { KeyRound, AlertCircle, Loader2 } from 'lucide-react';

export default function Login() {
  const [token, setToken] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { setToken: saveToken } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(e) {
    e.preventDefault();
    if (!token.trim()) {
      setError('Please enter an admin token');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const valid = await api.validateToken(token.trim());
      if (valid) {
        saveToken(token.trim());
        navigate('/', { replace: true });
      } else {
        setError('Invalid token. Check your ALB_ADMIN_TOKEN and try again.');
      }
    } catch {
      setError('Unable to connect to the load balancer. Is it running?');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-squid-ink px-4">
      <div className="w-full max-w-md">
        {/* Brand */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 bg-aws-orange rounded-2xl mb-4">
            <span className="text-squid-ink font-bold text-2xl">ALB</span>
          </div>
          <h1 className="text-xl font-semibold text-white">Management Console</h1>
          <p className="text-sm text-gray-400 mt-1">Sign in with your admin token</p>
        </div>

        {/* Login Card */}
        <div className="card">
          <div className="card-body">
            <form onSubmit={handleSubmit} className="space-y-4">
              {error && (
                <div className="flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700">
                  <AlertCircle size={16} className="flex-shrink-0 mt-0.5" />
                  <span>{error}</span>
                </div>
              )}

              <div>
                <label htmlFor="token" className="label">
                  Admin Token
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <KeyRound size={16} className="text-gray-400" />
                  </div>
                  <input
                    id="token"
                    type="password"
                    value={token}
                    onChange={(e) => setToken(e.target.value)}
                    placeholder="Enter your ALB_ADMIN_TOKEN"
                    className="input-field pl-10"
                    autoFocus
                    autoComplete="off"
                  />
                </div>
                <p className="mt-1.5 text-xs text-gray-500">
                  This is the value of the <code className="bg-gray-100 px-1 rounded">ALB_ADMIN_TOKEN</code> environment variable.
                </p>
              </div>

              <button type="submit" disabled={loading} className="btn-warning w-full justify-center">
                {loading ? (
                  <>
                    <Loader2 size={16} className="animate-spin" />
                    Verifying...
                  </>
                ) : (
                  'Sign in'
                )}
              </button>
            </form>
          </div>
        </div>

        <p className="text-center text-xs text-gray-500 mt-6">
          Token is stored in session storage and cleared when you close the tab.
        </p>
      </div>
    </div>
  );
}
