import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Server,
  GitBranch,
  Route,
  Settings,
  LogOut,
  Activity,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';

const navItems = [
  { to: '/', icon: LayoutDashboard, label: 'Dashboard' },
  { to: '/backends', icon: Server, label: 'Target Groups' },
  { to: '/algorithm', icon: GitBranch, label: 'Algorithm' },
  { to: '/routes', icon: Route, label: 'Routes' },
  { to: '/metrics', icon: Activity, label: 'Metrics' },
];

export default function Sidebar() {
  const { logout } = useAuth();

  return (
    <aside className="w-64 bg-squid-ink text-white flex flex-col min-h-screen fixed left-0 top-0 z-30">
      {/* Brand */}
      <div className="px-5 py-4 border-b border-white/10">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 bg-aws-orange rounded-lg flex items-center justify-center">
            <span className="text-squid-ink font-bold text-sm">ALB</span>
          </div>
          <div>
            <div className="font-semibold text-sm leading-tight">Application</div>
            <div className="text-xs text-gray-400 leading-tight">Load Balancer</div>
          </div>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 py-3 px-3 space-y-0.5 overflow-y-auto">
        <div className="px-2 py-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
          Management
        </div>
        {navItems.map(({ to, icon: Icon, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded text-sm transition-colors ${
                isActive
                  ? 'bg-aws-blue text-white'
                  : 'text-gray-300 hover:bg-white/5 hover:text-white'
              }`
            }
          >
            <Icon size={18} strokeWidth={1.5} />
            <span>{label}</span>
          </NavLink>
        ))}
      </nav>

      {/* Footer */}
      <div className="px-3 py-3 border-t border-white/10">
        <NavLink
          to="/settings"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2 rounded text-sm transition-colors ${
              isActive
                ? 'bg-aws-blue text-white'
                : 'text-gray-300 hover:bg-white/5 hover:text-white'
            }`
          }
        >
          <Settings size={18} strokeWidth={1.5} />
          <span>Settings</span>
        </NavLink>
        <button
          onClick={logout}
          className="flex items-center gap-3 px-3 py-2 rounded text-sm text-gray-400 hover:bg-white/5 hover:text-white transition-colors w-full"
        >
          <LogOut size={18} strokeWidth={1.5} />
          <span>Sign out</span>
        </button>
      </div>
    </aside>
  );
}
