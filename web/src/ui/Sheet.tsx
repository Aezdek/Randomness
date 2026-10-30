import { X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

interface SheetProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  children: ReactNode;
  size?: 'normal' | 'wide' | 'full';
  left?: ReactNode;
  right?: ReactNode;
  /** Slides up from the bottom on phones (menus, pickers). */
  bottom?: boolean;
  bodyClass?: string;
  hideHeader?: boolean;
}

/** Modal on desktop, bottom sheet on phones; springs in, Escape or a tap outside closes it. */
export function Sheet({ open, onClose, title, children, size = 'normal', left, right, bottom, bodyClass = '', hideHeader }: SheetProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
    };
  }, [open, onClose]);

  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div
          className={`scrim ${bottom ? 'bottom' : ''}`}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.18 }}
          onMouseDown={(e) => e.target === e.currentTarget && onClose()}
        >
          <motion.div
            role="dialog"
            aria-modal="true"
            className={`sheet ${size === 'normal' ? '' : size}`}
            initial={{ opacity: 0, scale: 0.96, y: bottom ? 40 : 12 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.97, y: bottom ? 40 : 8 }}
            transition={{ type: 'spring', stiffness: 420, damping: 34, mass: 0.8 }}
          >
            <div className="grabber" />
            {!hideHeader && (
              <div className="sheet-head">
                {left ?? (
                  <button className="icon-btn sm" onClick={onClose} aria-label="Close">
                    <X size={20} />
                  </button>
                )}
                <h2>{title}</h2>
                {right ?? <span style={{ width: 32 }} />}
              </div>
            )}
            <div className={`sheet-body ${bodyClass}`}>{children}</div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  );
}

export function Confirm({
  open,
  title,
  message,
  confirm,
  danger,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  message?: ReactNode;
  confirm: string;
  danger?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Sheet open={open} onClose={onClose} hideHeader bottom bodyClass="flush">
      <div style={{ padding: '28px 24px 20px', textAlign: 'center' }}>
        <h3 style={{ margin: 0, fontSize: 18, fontWeight: 700 }}>{title}</h3>
        {message && <p className="muted" style={{ margin: '8px 0 0' }}>{message}</p>}
      </div>
      <div className="menu">
        <button
          className={danger ? 'danger' : ''}
          style={{ justifyContent: 'center', fontWeight: 650 }}
          onClick={() => {
            onConfirm();
            onClose();
          }}
        >
          {confirm}
        </button>
        <button style={{ justifyContent: 'center' }} onClick={onClose}>
          Cancel
        </button>
      </div>
    </Sheet>
  );
}
