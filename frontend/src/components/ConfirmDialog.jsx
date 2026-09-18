import { AlertTriangle, X } from 'lucide-react';

export default function ConfirmDialog({ open, title, message, confirmLabel = 'Confirm', variant = 'danger', onConfirm, onCancel }) {
  if (!open) return null;

  const btnClass = variant === 'danger' ? 'btn-danger' : 'btn-warning';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="card w-full max-w-md mx-4 shadow-xl">
        <div className="card-header flex items-center justify-between">
          <h3 className="text-base font-semibold text-gray-900">{title}</h3>
          <button onClick={onCancel} className="text-gray-400 hover:text-gray-600">
            <X size={18} />
          </button>
        </div>
        <div className="card-body">
          <div className="flex gap-3">
            <div className="flex-shrink-0 mt-0.5">
              <AlertTriangle size={20} className="text-amber-500" />
            </div>
            <p className="text-sm text-gray-600">{message}</p>
          </div>
        </div>
        <div className="px-6 py-3 bg-gray-50 rounded-b-lg flex justify-end gap-3">
          <button onClick={onCancel} className="btn-secondary btn-sm">
            Cancel
          </button>
          <button onClick={onConfirm} className={`${btnClass} btn-sm`}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
