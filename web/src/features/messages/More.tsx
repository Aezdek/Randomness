// The smaller things a chat can hold: a file, a shared list, the day's question and a countdown. Their rules are in
// core/extras.ts; what they send is in core/more.ts.
import { CalendarClock, Check, Clock, Download, FileText, ListTodo, MessageCircleQuestion, Plus, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { cancelScheduled, MAX_LATER_TEXT, scheduledMessages, sendLater, type ScheduledDto } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { countdownLabel, dayOf, isDay, MAX_ANSWER, MAX_COUNTDOWN_TITLE, MAX_LIST_ITEM, MAX_LIST_ITEMS, MAX_LIST_TITLE, riskyFile, safeFile, sizeLabel } from '../../core/extras';
import { answerDaily, changeList, clearCountdown, daily, listState, saveFile, sendList, setCountdown } from '../../core/more';
import type { ChatCustom } from '../../core/chat';
import type { MessagePayload } from '../../core/payloads';
import { nameIn, useIndex } from '../../core/selectors';
import { displayText } from '../../core/safe';
import { toast, useApp, type Entity } from '../../core/store';
import type { ThreadDto } from '../../core/types';
import { Sheet } from '../../ui/Sheet';

const fail = (e: unknown) => toast(messageOf(e), 'error');

// ----------------------------------------------------------------------------------------------------------- files

export function FileCard({ message }: { message: Entity<MessagePayload> }) {
  const file = safeFile(message.payload.file);
  const [busy, setBusy] = useState(false);
  if (!file) return <span className="faint small">A file that couldn’t be shown</span>;
  const save = async () => {
    setBusy(true);
    try {
      await saveFile(file);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <button className="file-card" onClick={() => void save()} disabled={busy} aria-label={`Download ${file.name}`}>
      <span className="file-icon">
        <FileText size={20} />
      </span>
      <span className="file-text">
        <b>{file.name}</b>
        <span className="faint">{busy ? 'Decrypting…' : sizeLabel(file.size)}</span>
        {riskyFile(file.name) && <span className="file-warn">This kind of file can run. Open it only if you trust who sent it.</span>}
      </span>
      <Download size={18} className="file-get" />
    </button>
  );
}

// ----------------------------------------------------------------------------------------------------------- lists

export function ListCard({ message }: { message: Entity<MessagePayload> }) {
  const index = useIndex();
  const [adding, setAdding] = useState('');
  const state = listState(index, message);
  const left = state.items.filter((i) => !i.done).length;
  const add = () => {
    const text = adding.trim();
    if (!text) return;
    setAdding('');
    void changeList(message, { add: text }).catch(fail);
  };
  return (
    <div className="list-card">
      <div className="list-head">
        <ListTodo size={16} />
        <b>{state.title}</b>
        <span className="faint">{state.items.length ? `${left} left` : 'Empty'}</span>
      </div>
      <ul className="list-items">
        {state.items.map((item) => (
          <li key={item.id} className={item.done ? 'done' : ''}>
            <button className="list-tick" role="checkbox" aria-checked={item.done} aria-label={item.text} onClick={() => void changeList(message, item.done ? { undone: item.id } : { done: item.id }).catch(fail)}>
              {item.done && <Check size={13} />}
            </button>
            <span className="list-text">{item.text}</span>
            <button className="list-remove" aria-label={`Remove ${item.text}`} onClick={() => void changeList(message, { remove: item.id }).catch(fail)}>
              <Trash2 size={14} />
            </button>
          </li>
        ))}
      </ul>
      {state.items.length < MAX_LIST_ITEMS && (
        <form
          className="list-add"
          onSubmit={(e) => {
            e.preventDefault();
            add();
          }}
        >
          <input className="input" placeholder="Add an item" value={adding} maxLength={MAX_LIST_ITEM} onChange={(e) => setAdding(e.target.value)} aria-label="Add an item" />
          <button className="icon-btn sm" aria-label="Add" disabled={!adding.trim()}>
            <Plus size={18} />
          </button>
        </form>
      )}
    </div>
  );
}

export function ListSheet({ open, onClose, threadId }: { open: boolean; onClose: () => void; threadId: string }) {
  const [title, setTitle] = useState('');
  const [items, setItems] = useState('');
  const close = () => (setTitle(''), setItems(''), onClose());
  return (
    <Sheet open={open} onClose={close} title="Shared list" bottom>
      <form
        className="chat-poll-form"
        onSubmit={(e) => {
          e.preventDefault();
          void sendList(threadId, title, items.split('\n')).then(close).catch(fail);
        }}
      >
        <input className="input" placeholder="Name (Groceries, Films to watch…)" value={title} maxLength={MAX_LIST_TITLE} onChange={(e) => setTitle(e.target.value)} autoFocus aria-label="List name" />
        <textarea className="input" rows={4} placeholder="First items, one per line (optional)" value={items} onChange={(e) => setItems(e.target.value)} aria-label="First items" />
        <button className="btn btn-primary btn-block" disabled={!title.trim()}>
          Send list
        </button>
        <p className="faint small">Everyone in the chat can add items and tick them off.</p>
      </form>
    </Sheet>
  );
}

// ------------------------------------------------------------------------------------------------ the day's question

/** An answer in the conversation: shown once everyone has answered that day, or once the day is over. */
export function DailyCard({ message, thread }: { message: Entity<MessagePayload>; thread: ThreadDto }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const d = message.payload.daily;
  if (!d || !isDay(d.day)) return <span className="faint small">An answer that couldn’t be shown</span>;
  const state = daily(index, thread, d.day);
  const shown = message.dto.authorId === me.id || state.revealed || d.day < dayOf();
  return (
    <div className="daily-card">
      <span className="daily-label">
        <MessageCircleQuestion size={14} /> Question of the day
      </span>
      <b>{state.question}</b>
      {shown ? <p>{displayText(d.answer, MAX_ANSWER)}</p> : <p className="faint">Answered. You’ll see it once everyone has answered.</p>}
    </div>
  );
}

export function DailySheet({ open, onClose, thread }: { open: boolean; onClose: () => void; thread: ThreadDto }) {
  const index = useIndex();
  const me = useApp((s) => s.me!);
  const users = useApp((s) => s.users);
  const [answer, setAnswer] = useState('');
  const state = daily(index, thread);
  const mine = state.answers[me.id];
  const waiting = thread.members.filter((m) => !(m.userId in state.answers)).map((m) => (m.userId === me.id ? 'you' : nameIn(thread.id, m.userId, index, users)));
  return (
    <Sheet open={open} onClose={onClose} title="Question of the day" bottom>
      <div className="chat-poll-form">
        <b className="daily-question">{state.question}</b>
        {mine === undefined ? (
          <form
            className="chat-poll-form"
            onSubmit={(e) => {
              e.preventDefault();
              void answerDaily(index, thread, me.id, answer).then(() => setAnswer('')).catch(fail);
            }}
          >
            <textarea className="input" rows={3} placeholder="Your answer" value={answer} maxLength={MAX_ANSWER} onChange={(e) => setAnswer(e.target.value)} autoFocus aria-label="Your answer" />
            <button className="btn btn-primary btn-block" disabled={!answer.trim()}>
              Answer
            </button>
          </form>
        ) : state.revealed ? (
          <ul className="daily-answers">
            {thread.members.map((m) => (
              <li key={m.userId}>
                <b>{m.userId === me.id ? 'You' : nameIn(thread.id, m.userId, index, users)}</b>
                <span>{state.answers[m.userId]}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="faint">You answered. Waiting for {waiting.join(', ')}.</p>
        )}
        <p className="faint small">A new question every day. Answers stay out of sight until everyone in the chat has answered.</p>
      </div>
    </Sheet>
  );
}

// ------------------------------------------------------------------------------------------------------ countdowns

export function CountdownBar({ custom }: { custom: ChatCustom }) {
  const label = countdownLabel(custom.countdownTitle, custom.countdownAt);
  if (!label) return null;
  return (
    <div className="countdown-bar">
      <CalendarClock size={14} /> {label}
    </div>
  );
}

export function CountdownSheet({ open, onClose, threadId, custom }: { open: boolean; onClose: () => void; threadId: string; custom: ChatCustom }) {
  const [title, setTitle] = useState('');
  const [date, setDate] = useState('');
  const at = date ? new Date(`${date}T12:00`).getTime() : NaN;
  const close = () => (setTitle(''), setDate(''), onClose());
  return (
    <Sheet open={open} onClose={close} title="Countdown" bottom>
      <form
        className="chat-poll-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (!title.trim() || !Number.isFinite(at)) return;
          void setCountdown(threadId, title, at).then(close).catch(fail);
        }}
      >
        <input className="input" placeholder="What for? (Trip, Together since…)" value={title} maxLength={MAX_COUNTDOWN_TITLE} onChange={(e) => setTitle(e.target.value)} autoFocus aria-label="What for" />
        <label className="field">
          <span>Date</span>
          <input className="input" type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
        </label>
        <button className="btn btn-primary btn-block" disabled={!title.trim() || !Number.isFinite(at)}>
          Set countdown
        </button>
        {custom.countdownTitle && (
          <button type="button" className="btn btn-secondary btn-block" onClick={() => void clearCountdown(threadId).then(close).catch(fail)}>
            Remove “{custom.countdownTitle}”
          </button>
        )}
        <p className="faint small">Shows at the top of the chat for everyone: days to go, or days since a date in the past.</p>
      </form>
    </Sheet>
  );
}

// ------------------------------------------------------------------------------------------------------ send later

const LATER = 'hush.later';
/** What each waiting message says, kept on the device that wrote it: the server only holds it sealed. */
function laterTexts(): Record<string, string> {
  try {
    const v = JSON.parse(localStorage.getItem(LATER) ?? '{}');
    return v && typeof v === 'object' ? v : {};
  } catch {
    return {};
  }
}
function rememberLater(id: string, text: string | null) {
  const all = laterTexts();
  if (text === null) delete all[id];
  else all[id] = text.slice(0, 200);
  try {
    localStorage.setItem(LATER, JSON.stringify(all));
  } catch {
    // Without storage the list just says "A message".
  }
}

const localInput = (at: number) => new Date(at - new Date(at).getTimezoneOffset() * 60_000).toISOString().slice(0, 16);

export function LaterSheet({ open, onClose, threadId }: { open: boolean; onClose: () => void; threadId: string }) {
  const [text, setText] = useState('');
  const [when, setWhen] = useState(() => localInput(Date.now() + 60 * 60_000));
  const [waiting, setWaiting] = useState<ScheduledDto[]>([]);
  const refresh = () => void scheduledMessages().then((all) => setWaiting(all.filter((m) => m.threadId === threadId))).catch(() => undefined);
  useEffect(() => {
    if (open) refresh();
  }, [open, threadId]); // eslint-disable-line react-hooks/exhaustive-deps
  const at = new Date(when).getTime();
  const texts = laterTexts();
  return (
    <Sheet open={open} onClose={onClose} title="Send later" bottom>
      <form
        className="chat-poll-form"
        onSubmit={(e) => {
          e.preventDefault();
          void sendLater(threadId, text, at)
            .then((m) => (rememberLater(m.id, text.trim()), setText(''), refresh(), toast('It will arrive then')))
            .catch(fail);
        }}
      >
        <textarea className="input" rows={3} placeholder="Message" value={text} maxLength={MAX_LATER_TEXT} onChange={(e) => setText(e.target.value)} autoFocus aria-label="Message to send later" />
        <label className="field">
          <span>When</span>
          <input className="input" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} required />
        </label>
        <button className="btn btn-primary btn-block" disabled={!text.trim() || !Number.isFinite(at)}>
          <Clock size={16} /> Send then
        </button>
        {waiting.length > 0 && (
          <ul className="later-list" aria-label="Waiting to be sent">
            {waiting.map((m) => (
              <li key={m.id}>
                <span className="list-text">
                  <b>{texts[m.id] ?? 'A message'}</b>
                  <span className="faint small">{m.failed ? `Not sent: ${m.failed}` : new Date(m.releaseAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</span>
                </span>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => void cancelScheduled(m.id).then(() => (rememberLater(m.id, null), refresh())).catch(fail)}>
                  {m.failed ? 'Clear' : 'Cancel'}
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="faint small">Your server keeps it sealed and delivers it at that time, even if this device is off. Words only, up to 60 days ahead.</p>
      </form>
    </Sheet>
  );
}
