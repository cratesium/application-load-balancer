import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Layout from './components/Layout';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Backends from './pages/Backends';
import BackendDetail from './pages/BackendDetail';
import Algorithm from './pages/Algorithm';
import RoutesPage from './pages/Routes';
import Metrics from './pages/Metrics';
import Settings from './pages/Settings';

function ProtectedRoute({ children }) {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Dashboard />} />
        <Route path="backends" element={<Backends />} />
        <Route path="backends/:id" element={<BackendDetail />} />
        <Route path="algorithm" element={<Algorithm />} />
        <Route path="routes" element={<RoutesPage />} />
        <Route path="metrics" element={<Metrics />} />
        <Route path="settings" element={<Settings />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
