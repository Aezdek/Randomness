import jsQR from 'jsqr';
import { Camera, ShieldAlert, ShieldCheck } from 'lucide-react';
import QRCode from 'qrcode';
import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useApp, toast } from '../../core/store';
import { engine } from '../../core/sync';
import { groupDigits, parseQr, qrPayload, safetyPair } from '../../crypto/safety';
import { b64, unb64 } from '../../crypto/sodium';
import { Avatar } from '../../ui/Avatar';
import { Verified } from '../../ui/bits';

export function MyQr({ size = 220 }: { size?: number }) {
  const me = useApp((s) => s.me!);
  const keys = useApp((s) => s.keys!);
  const [svg, setSvg] = useState('');
  useEffect(() => {
    void QRCode.toString(qrPayload(me.id, keys.signPk), { type: 'svg', margin: 1, errorCorrectionLevel: 'M', color: { dark: '#0b0b0e', light: '#ffffff' } }).then(setSvg);
  }, [me.id, keys.signPk]);
  return <div className="qr" style={{ width: size, height: size }} dangerouslySetInnerHTML={{ __html: svg }} />;
}

function Scanner({ onResult, onClose }: { onResult: (text: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    let stream: MediaStream | null = null;
    let raf = 0;
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: 'environment' } })
      .then((s) => {
        stream = s;
        if (video.current) video.current.srcObject = s;
        const tick = () => {
          const v = video.current;
          if (v && v.videoWidth) {
            canvas.width = v.videoWidth;
            canvas.height = v.videoHeight;
            ctx.drawImage(v, 0, 0);
            const code = jsQR(ctx.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height);
            if (code?.data) return onResult(code.data);
          }
          raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
      })
      .catch(() => {
        toast('Camera unavailable', 'error');
        onClose();
      });
    return () => {
      cancelAnimationFrame(raf);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [onResult, onClose]);
  return (
    <div className="scanner">
      <video ref={video} autoPlay playsInline muted />
      <span className="scanner-frame" />
      <button className="btn btn-secondary" onClick={onClose}>
        Cancel
      </button>
    </div>
  );
}

/** Safety numbers and QR verification: proof the server has not swapped anyone's keys. */
export function Verify() {
  const { userId = '' } = useParams();
  const user = useApp((s) => s.users[userId]);
  const pin = useApp((s) => s.pins[userId]);
  const keys = useApp((s) => s.keys!);
  const [scanning, setScanning] = useState(false);
  if (!user) return null;
  const digits = safetyPair(keys.signPk, unb64(user.signPk));
  const verified = pin?.verified;
  const changed = !!pin?.changedFrom;

  const onScan = (text: string) => {
    setScanning(false);
    const parsed = parseQr(text);
    if (!parsed || parsed.userId !== userId) return toast('That isn’t their Hush code', 'error');
    if (b64(parsed.signPk) !== user.signPk) return toast('Keys don’t match! Don’t trust this account until you find out why.', 'error');
    void engine.acknowledgeKeyChange(userId, true);
    toast(`${user.username} is verified`, 'success');
  };

  return (
    <div className="page narrow verify">
      <h1 className="page-title">Security code</h1>
      <div className="verify-head">
        <Avatar userId={userId} size={72} />
        <div>
          <div className="row" style={{ gap: 6 }}>
            <b style={{ fontSize: 18 }}>{user.username}</b>
            {verified && <Verified size={18} />}
          </div>
          <span className={verified ? 'verified-text' : 'faint'}>{verified ? 'Verified' : changed ? 'Their security code changed' : 'Not verified yet'}</span>
        </div>
      </div>
      {changed && (
        <div className="key-warning">
          <ShieldAlert size={18} /> {user.username}’s keys changed. This happens when they reset their keys. Compare codes in person before trusting new messages.
        </div>
      )}
      <div className="safety-grid">
        {groupDigits(digits).map((g, i) => (
          <span key={i} className="tabular">
            {g}
          </span>
        ))}
      </div>
      <p className="muted" style={{ textAlign: 'center' }}>
        Compare these numbers with {user.username} in person (or on a call), or scan each other’s code. If they match, nobody is in the middle.
      </p>
      <div className="verify-qr">
        <MyQr />
        <span className="faint">Your code</span>
      </div>
      <div className="row" style={{ justifyContent: 'center', gap: 12, flexWrap: 'wrap' }}>
        <button className="btn btn-primary btn-lg" onClick={() => setScanning(true)}>
          <Camera size={18} /> Scan their code
        </button>
        <button className="btn btn-secondary btn-lg" onClick={() => void engine.acknowledgeKeyChange(userId, !verified)}>
          <ShieldCheck size={18} /> {verified ? 'Clear verification' : changed ? 'Accept new code' : 'Mark as verified'}
        </button>
      </div>
      {scanning && <Scanner onResult={onScan} onClose={() => setScanning(false)} />}
    </div>
  );
}
