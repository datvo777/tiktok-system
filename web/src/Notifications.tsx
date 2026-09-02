import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getNotifications, markNotificationRead, type NotificationItem } from './api';
import { CheckIcon, CommentIcon, FlagIcon, HeartIcon, InboxIcon, UsersIcon } from './icons';

/** Icon plus colour tone per notification kind, so the list scans at a glance. */
const TYPE_STYLE: Record<string, { tone: string; icon: React.ReactNode }> = {
  MODERATION_REJECTED: { tone: 'tone-danger', icon: <FlagIcon size={18} /> },
  MODERATION_REINSTATED: { tone: 'tone-success', icon: <CheckIcon size={18} /> },
  APPEAL_DENIED: { tone: 'tone-danger', icon: <FlagIcon size={18} /> },
  NEW_COMMENT: { tone: 'tone-brand', icon: <CommentIcon size={18} /> },
  NEW_FOLLOWER: { tone: 'tone-brand', icon: <UsersIcon size={18} /> },
  NEW_LIKE: { tone: 'tone-brand', icon: <HeartIcon size={18} filled /> },
};

/**
 * Timestamps arrive as ISO strings; a wall-clock string is noise in a list you
 * skim, so show the age instead and keep the exact value in the tooltip.
 */
function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return iso;
  const seconds = Math.max(0, (Date.now() - then) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = seconds / 60;
  if (minutes < 60) return `${Math.floor(minutes)}m ago`;
  const hours = minutes / 60;
  if (hours < 24) return `${Math.floor(hours)}h ago`;
  const days = hours / 24;
  if (days < 7) return `${Math.floor(days)}d ago`;
  return new Date(then).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** Basic in-app notifications (brief section 20, Milestone 7). */
export function Notifications() {
  const queryClient = useQueryClient();

  const list = useQuery({
    queryKey: ['notifications'],
    queryFn: getNotifications,
    refetchInterval: 10_000,
  });

  const markRead = useMutation({
    mutationFn: markNotificationRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });

  const items: NotificationItem[] = list.data ?? [];
  const unread = items.filter((n) => !n.read);

  if (list.isPending) {
    return (
      <div className="empty">
        <div className="spinner" />
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div className="empty">
        <InboxIcon />
        <span className="empty-text">No notifications yet.</span>
      </div>
    );
  }

  return (
    <div>
      {unread.length > 0 && (
        <div className="sheet-toolbar">
          <span className="sheet-toolbar-label">
            {unread.length} unread {unread.length === 1 ? 'notification' : 'notifications'}
          </span>
          <button
            className="btn-ghost btn-sm"
            disabled={markRead.isPending}
            onClick={() => unread.forEach((n) => markRead.mutate(n.notificationId))}
          >
            Mark all read
          </button>
        </div>
      )}

      <ul className="notif-list">
        {items.map((n) => {
          const style = TYPE_STYLE[n.type];
          return (
            <li
              key={n.notificationId}
              data-testid={`notification-${n.notificationId}`}
              className={`notif-item${n.read ? '' : ' unread'}`}
            >
              <span className={`notif-icon ${style?.tone ?? ''}`}>{style?.icon ?? <InboxIcon size={18} />}</span>
              <div className="notif-body">
                <div className="notif-message">{n.message}</div>
                <div className="notif-foot">
                  <span className="notif-time" title={n.createdAt}>
                    {relativeTime(n.createdAt)}
                  </span>
                  {!n.read && (
                    <button
                      className="btn-ghost btn-sm"
                      onClick={() => markRead.mutate(n.notificationId)}
                      disabled={markRead.isPending}
                    >
                      Mark read
                    </button>
                  )}
                </div>
              </div>
              {!n.read && <span className="notif-dot" />}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
