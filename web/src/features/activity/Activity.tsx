import { AtSign, Heart, MessageCircle, MessageSquareQuote, UserCheck } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { acceptFollow, declineFollow } from '../../core/compose';
import { messageOf } from '../../core/errors';
import { Kind, type ActivityPayload, type AnswerPayload, type CommentPayload } from '../../core/payloads';
import { useIndex } from '../../core/selectors';
import { toast, useApp, type Entity } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { Ago, Empty } from '../../ui/bits';
import { MediaImage } from '../../ui/Media';
import { coverOf } from '../profile/PostGrid';
import { FollowButton } from '../profile/FollowButton';

interface Item {
  id: string;
  at: number;
  actor: string;
  icon: React.ReactNode;
  text: React.ReactNode;
  target?: Entity;
  link?: string;
}

const SEEN_KEY = 'aezgram.activitySeen';
const seenAt = () => Number(localStorage.getItem(SEEN_KEY) ?? 0);

function rootOf(entities: Record<string, Entity>, entity: Entity): Entity {
  let current = entity;
  while (current.dto.parentId && entities[current.dto.parentId]) current = entities[current.dto.parentId];
  return current;
}

function useItems(): Item[] {
  const index = useIndex();
  const entities = useApp((s) => s.entities);
  const me = useApp((s) => s.me!.id);
  return useMemo(() => {
    const out: Item[] = [];
    for (const entity of Object.values(entities)) {
      const { dto } = entity;
      if (dto.authorId === me) continue;
      const parent = dto.parentId ? entities[dto.parentId] : undefined;
      if (dto.kind === Kind.LIKE && parent?.dto.authorId === me) {
        const what = parent.dto.kind === Kind.COMMENT ? 'liked your comment' : parent.dto.kind === Kind.STORY ? 'liked your story' : parent.dto.kind === Kind.REEL ? 'liked your reel' : 'liked your post';
        out.push({ id: dto.id, at: dto.receivedAt, actor: dto.authorId, icon: <Heart size={12} fill="#fff" strokeWidth={0} />, text: what, target: rootOf(entities, parent) });
      } else if (dto.kind === Kind.COMMENT && parent && (parent.dto.authorId === me || rootOf(entities, parent).dto.authorId === me)) {
        const text = (entity.payload as CommentPayload).text;
        out.push({
          id: dto.id,
          at: dto.receivedAt,
          actor: dto.authorId,
          icon: <MessageCircle size={12} fill="#fff" strokeWidth={0} />,
          text: (
            <>
              {parent.dto.kind === Kind.COMMENT ? 'replied: ' : 'commented: '}
              <span className="muted">{text.length > 80 ? text.slice(0, 80) + '…' : text}</span>
            </>
          ),
          target: rootOf(entities, parent),
        });
      } else if (dto.kind === Kind.VOTE && parent?.dto.authorId === me) {
        out.push({ id: dto.id, at: dto.receivedAt, actor: dto.authorId, icon: <MessageSquareQuote size={12} />, text: 'responded to your story', link: `/stories/${me}` });
      }
    }
    for (const a of index.activity) {
      const p = a.payload as ActivityPayload;
      out.push({
        id: a.dto.id,
        at: a.dto.receivedAt,
        actor: a.dto.authorId,
        icon: <AtSign size={12} />,
        text: p.type === 'tag' ? 'tagged you in a post' : (
          <>
            mentioned you: <span className="muted">{p.text}</span>
          </>
        ),
        target: entities[p.objectId],
        link: `/p/${p.objectId}`,
      });
    }
    for (const a of index.answers) {
      if (a.dto.authorId === me) continue;
      out.push({
        id: a.dto.id,
        at: a.dto.receivedAt,
        actor: a.dto.authorId,
        icon: <MessageSquareQuote size={12} />,
        text: (
          <>
            answered your question: <span className="muted">{(a.payload as AnswerPayload).text}</span>
          </>
        ),
      });
    }
    return out.sort((a, b) => b.at - a.at);
  }, [entities, index, me]);
}

export function useActivityBadge(): number {
  const items = useItems();
  const requests = useApp((s) => s.followRequests.length);
  const { pathname } = useLocation();
  const [seen, setSeen] = useState(seenAt);
  useEffect(() => {
    if (pathname === '/activity') setSeen(seenAt());
  }, [pathname, items]);
  return requests + items.filter((i) => i.at > seen).length;
}

function Section({ title, items }: { title: string; items: Item[] }) {
  const users = useApp((s) => s.users);
  const navigate = useNavigate();
  const location = useLocation();
  if (!items.length) return null;
  return (
    <section className="activity-section">
      <h3>{title}</h3>
      {items.map((item) => {
        const cover = item.target ? coverOf(item.target) : null;
        const user = users[item.actor];
        return (
          <div
            key={item.id}
            className="activity-row"
            role="button"
            onClick={() => (item.target ? navigate(`/p/${item.target.dto.id}`, { state: { background: location } }) : item.link && navigate(item.link))}
          >
            <span className="activity-avatar">
              <Avatar userId={item.actor} size={44} />
              <span className="activity-icon">{item.icon}</span>
            </span>
            <p>
              <Link to={`/u/${user?.username}`} className="author-name" onClick={(e) => e.stopPropagation()}>
                {user?.username ?? 'someone'}
              </Link>{' '}
              {item.text} <Ago at={item.at} />
            </p>
            {cover && <MediaImage media={cover} className="activity-thumb" />}
          </div>
        );
      })}
    </section>
  );
}

export function Activity() {
  const items = useItems();
  const requests = useApp((s) => s.followRequests);
  const users = useApp((s) => s.users);
  const [lastSeen] = useState(seenAt);
  useEffect(() => {
    localStorage.setItem(SEEN_KEY, String(Date.now()));
  }, [items]);
  const now = Date.now();
  const day = 86_400_000;
  const followsYou = Object.values(users).filter((u) => u.followsMe === 'following' && u.followState === 'none' && !u.blockedByMe);
  const run = (action: Promise<void>) => action.catch((e) => toast(messageOf(e), 'error'));

  return (
    <div className="page narrow activity">
      <h1 className="page-title">Notifications</h1>
      {requests.length > 0 && (
        <section className="activity-section">
          <h3>Follow requests</h3>
          {requests.map((id) => (
            <div className="activity-row" key={id}>
              <Avatar userId={id} size={44} />
              <p>
                <Link to={`/u/${users[id]?.username}`} className="author-name">
                  {users[id]?.username}
                </Link>{' '}
                wants to follow you
              </p>
              <div className="row" style={{ gap: 8 }}>
                <button className="btn btn-brand btn-sm" onClick={() => run(acceptFollow(id))}>
                  Confirm
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => run(declineFollow(id))}>
                  Delete
                </button>
              </div>
            </div>
          ))}
        </section>
      )}
      {items.length || followsYou.length ? (
        <>
          <Section title="New" items={items.filter((i) => i.at > lastSeen)} />
          <Section title="Today" items={items.filter((i) => i.at <= lastSeen && now - i.at < day)} />
          <Section title="This week" items={items.filter((i) => i.at <= lastSeen && now - i.at >= day && now - i.at < 7 * day)} />
          <Section title="Earlier" items={items.filter((i) => i.at <= lastSeen && now - i.at >= 7 * day)} />
          {followsYou.length > 0 && (
            <section className="activity-section">
              <h3>Follows you</h3>
              {followsYou.map((u) => (
                <div className="activity-row" key={u.id}>
                  <span className="activity-avatar">
                    <Avatar userId={u.id} size={44} />
                    <span className="activity-icon">
                      <UserCheck size={12} />
                    </span>
                  </span>
                  <p>
                    <Link to={`/u/${u.username}`} className="author-name">
                      {u.username}
                    </Link>{' '}
                    started following you
                  </p>
                  <FollowButton user={u} />
                </div>
              ))}
            </section>
          )}
        </>
      ) : (
        !requests.length && (
          <Empty icon={<Heart size={36} />} title="Activity on your posts">
            When someone likes or comments on your posts, you’ll see it here.
          </Empty>
        )
      )}
    </div>
  );
}
