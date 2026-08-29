import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getNotifications, markNotificationRead, type NotificationItem } from './api';

const TYPE_ICON: Record<string, string> = {
  MODERATION_REJECTED: '⚑',
  MODERATION_REINSTATED: '✓',
  APPEAL_DENIED: '⚑',
  NEW_COMMENT: '💬',
  NEW_FOLLOWER: '★',
};

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
  const unreadCount = items.filter((n) => !n.read).length;

  return (
    <section className="card">
      <div className="card-head">
        <h2>
          Notifications{unreadCount > 0 ? <span className="count-pill" style={{ marginLeft: '0.5rem' }}>{unreadCount}</span> : null}
        </h2>
        <span className="card-eyebrow">Milestone 7</span>
      </div>
      {list.isPending && <p className="card-desc">Loading...</p>}
      {items.length === 0 && !list.isPending && <div className="feed-empty">No notifications yet.</div>}
      <ul className="notif-list">
        {items.map((n) => (
          <li key={n.notificationId} data-testid={`notification-${n.notificationId}`} className={`notif-item${n.read ? '' : ' unread'}`}>
            <span className="notif-icon">{TYPE_ICON[n.type] ?? '•'}</span>
            <div className="notif-body">
              <div className="notif-message">{n.message}</div>
              <div className="notif-footer-row">
                <span className="notif-time">{n.createdAt}</span>
                {!n.read && (
                  <button className="btn-ghost btn-sm" onClick={() => markRead.mutate(n.notificationId)} disabled={markRead.isPending}>
                    Mark read
                  </button>
                )}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
