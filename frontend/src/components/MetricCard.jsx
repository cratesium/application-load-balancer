export default function MetricCard({ icon: Icon, label, value, subtext, color = 'blue' }) {
  const colorMap = {
    blue: 'bg-blue-50 text-blue-600',
    green: 'bg-emerald-50 text-emerald-600',
    orange: 'bg-orange-50 text-orange-600',
    red: 'bg-red-50 text-red-600',
    purple: 'bg-purple-50 text-purple-600',
    gray: 'bg-gray-100 text-gray-600',
  };

  return (
    <div className="card card-body flex items-start gap-4">
      <div className={`p-2.5 rounded-lg ${colorMap[color] || colorMap.blue}`}>
        <Icon size={22} strokeWidth={1.5} />
      </div>
      <div className="min-w-0">
        <p className="text-xs font-medium text-gray-500 uppercase tracking-wide">{label}</p>
        <p className="text-2xl font-semibold text-gray-900 mt-0.5">{value}</p>
        {subtext && <p className="text-xs text-gray-500 mt-0.5">{subtext}</p>}
      </div>
    </div>
  );
}
