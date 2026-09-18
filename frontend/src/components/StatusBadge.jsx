const statusConfig = {
  UP: { bg: 'bg-emerald-50', text: 'text-emerald-700', dot: 'bg-emerald-500', ring: 'ring-emerald-500/20' },
  DOWN: { bg: 'bg-red-50', text: 'text-red-700', dot: 'bg-red-500', ring: 'ring-red-500/20' },
  DRAINING: { bg: 'bg-amber-50', text: 'text-amber-700', dot: 'bg-amber-500', ring: 'ring-amber-500/20' },
  DISABLED: { bg: 'bg-gray-100', text: 'text-gray-600', dot: 'bg-gray-400', ring: 'ring-gray-400/20' },
  CLOSED: { bg: 'bg-emerald-50', text: 'text-emerald-700', dot: 'bg-emerald-500', ring: 'ring-emerald-500/20' },
  OPEN: { bg: 'bg-red-50', text: 'text-red-700', dot: 'bg-red-500', ring: 'ring-red-500/20' },
  HALF_OPEN: { bg: 'bg-amber-50', text: 'text-amber-700', dot: 'bg-amber-500', ring: 'ring-amber-500/20' },
};

export default function StatusBadge({ status, size = 'md' }) {
  const config = statusConfig[status] || statusConfig.DOWN;
  const sizeClasses = size === 'sm' ? 'text-xs px-2 py-0.5' : 'text-xs px-2.5 py-1';

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full font-medium ring-1 ring-inset ${config.bg} ${config.text} ${config.ring} ${sizeClasses}`}
    >
      <span className={`w-1.5 h-1.5 rounded-full ${config.dot}`} />
      {status}
    </span>
  );
}
