import { Clapperboard, Film, Grid3x3, MessageSquareQuote, Radio } from 'lucide-react';
import { motion } from 'motion/react';
import { useUi } from '../../core/ui';
import { openCreate } from '../../core/ui';
import { Sheet } from '../../ui/Sheet';
import { calls } from '../calls/calls';
import { CreateNote } from './CreateNote';
import { CreatePost } from './CreatePost';
import { CreateReel } from './CreateReel';
import { CreateStory } from './CreateStory';

const OPTIONS = [
  { id: 'post', label: 'Post', hint: 'Photos and videos on your profile', icon: Grid3x3 },
  { id: 'story', label: 'Story', hint: 'Disappears after 24 hours', icon: Film },
  { id: 'reel', label: 'Reel', hint: 'A full-screen vertical video', icon: Clapperboard },
  { id: 'note', label: 'Note', hint: 'A 60-character thought on your avatar', icon: MessageSquareQuote },
  { id: 'live', label: 'Live', hint: 'Broadcast to your followers', icon: Radio },
] as const;

export function CreateHost() {
  const create = useUi((s) => s.create);
  const menu = useUi((s) => s.createMenu);
  const close = () => useUi.setState({ create: null });
  return (
    <>
      <Sheet open={menu} onClose={() => useUi.setState({ createMenu: false })} title="Create" bottom bodyClass="flush">
        <div className="create-menu">
          {OPTIONS.map((option, i) => (
            <motion.button
              key={option.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: i * 0.035 }}
              onClick={() => (option.id === 'live' ? (useUi.setState({ createMenu: false }), calls.startLive()) : openCreate(option.id))}
            >
              <span className="create-icon">
                <option.icon size={22} />
              </span>
              <span className="create-text">
                <b>{option.label}</b>
                <span className="faint">{option.hint}</span>
              </span>
            </motion.button>
          ))}
        </div>
      </Sheet>
      {create === 'post' && <CreatePost onClose={close} />}
      {create === 'story' && <CreateStory onClose={close} />}
      {create === 'reel' && <CreateReel onClose={close} />}
      {create === 'note' && <CreateNote onClose={close} />}
    </>
  );
}
