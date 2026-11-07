import { motion } from 'motion/react';
import { useLayoutEffect, type ReactNode, type RefObject } from 'react';

/** Small pieces the landing page and the downloads page share. */

/** Apple's easing for things that slide and fade into place. */
export const EASE = [0.28, 0.11, 0.32, 1] as const;

/** Fades and lifts its content into place the first time it scrolls into view. */
export function Reveal({ children, delay = 0, className = '' }: { children: ReactNode; delay?: number; className?: string }) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 36 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-80px' }}
      transition={{ duration: 1, delay, ease: EASE }}
    >
      {children}
    </motion.div>
  );
}

export function Eyebrow({ children }: { children: ReactNode }) {
  return <p className="lp-eyebrow">{children}</p>;
}

/**
 * Fits a drawn screen (laid out at `design` px wide) to the device it sits in by zooming it, which lays it out again
 * at the new size rather than stretching a picture of it, so it stays sharp at any size.
 */
export function useFitScreen(ref: RefObject<HTMLElement | null>, design: number) {
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const fit = () => {
      if (!el.clientWidth) return;
      const zoom = el.clientWidth / design;
      el.style.setProperty('--dw', `${design}px`);
      el.style.setProperty('--z', String(zoom));
      el.style.setProperty('--ih', `${el.clientHeight / zoom}px`);
    };
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(el);
    return () => observer.disconnect();
  }, [ref, design]);
}
