import { animate, motion, useInView, useReducedMotion, useScroll, useSpring, useTransform } from 'motion/react';
import { Fragment, useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react';

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

/** A heading whose words rise into place one after another, each from behind its own edge. */
export function RiseText({ text, as: Tag = 'h2', className }: { text: string; as?: 'h2' | 'h1' | 'p'; className?: string }) {
  const MotionTag = motion[Tag];
  return (
    <MotionTag className={className} initial="hidden" whileInView="shown" viewport={{ once: true, margin: '-60px' }} aria-label={text}>
      {text.split(' ').map((word, i) => (
        <Fragment key={i}>
          {i > 0 && ' '}
          <span className="lp-rise-word" aria-hidden>
            <motion.span
              variants={{ hidden: { y: '105%', opacity: 0 }, shown: { y: '0%', opacity: 1 } }}
              transition={{ duration: 0.9, delay: i * 0.06, ease: EASE }}
            >
              {word}
            </motion.span>
          </span>
        </Fragment>
      ))}
    </MotionTag>
  );
}

/** Grows from slightly small and dim to full size as it scrolls up into view. */
export function ScrollGrow({ children, className = '', from = 0.88 }: { children: ReactNode; className?: string; from?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const still = useReducedMotion() ?? false;
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'start 0.3'] });
  const p = useSpring(scrollYProgress, { stiffness: 200, damping: 34, mass: 0.4 });
  const scale = useTransform(p, [0, 1], [from, 1]);
  const opacity = useTransform(p, [0, 0.6], [0.25, 1]);
  const y = useTransform(p, [0, 1], [70, 0]);
  return (
    <motion.div ref={ref} className={className} style={still ? undefined : { scale, opacity, y }}>
      {children}
    </motion.div>
  );
}

/** Moves a little faster or slower than the page as it scrolls past, for depth. */
export function Drift({ children, className = '', speed = 40 }: { children: ReactNode; className?: string; speed?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const still = useReducedMotion() ?? false;
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'end start'] });
  const y = useTransform(scrollYProgress, [0, 1], [speed, -speed]);
  return (
    <motion.div ref={ref} className={className} style={still ? undefined : { y }}>
      {children}
    </motion.div>
  );
}

/** Counts to a number the first time it comes into view. */
export function CountUp({ to, from = 0, className }: { to: number; from?: number; className?: string }) {
  const ref = useRef<HTMLSpanElement>(null);
  const inView = useInView(ref, { once: true, margin: '-80px' });
  const still = useReducedMotion() ?? false;
  const [value, setValue] = useState(still ? to : from);
  useEffect(() => {
    if (!inView || still) return;
    const controls = animate(from, to, { duration: 1.6, ease: EASE, onUpdate: (v) => setValue(Math.round(v)) });
    return () => controls.stop();
  }, [inView, still, from, to]);
  return (
    <span ref={ref} className={className}>
      {value.toLocaleString('en')}
    </span>
  );
}
