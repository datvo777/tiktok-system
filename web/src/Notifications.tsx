import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getNotifications, markAllNotificationsRead, markNotificationRead, type NotificationItem } from './api';
import { CheckIcon, CommentIcon, FlagIcon, HeartIcon, InboxIcon, UsersIcon } from './icons';
import { navigate, videoPath } from './router';
import { relativeTime } from './ui';

/** Icon plus colour tone per notification kind, so the list scans at a glance. */
const TYPE_STYLE: Record<string, { tone: string; icon: React.ReactNode }> = {
  MODERATION_REJECTED: { tone: 'tone-danger', icon: <FlagIcon size={18} /> },
  MODERATION_REINSTATED: { tone: 'tone-success', icon: <CheckIcon size={18} /> },
  APPEAL_DENIED: { tone: 'tone-danger', icon: <FlagIcon size={18} /> },
  VIDEO_PUBLISHED: { tone: 'tone-success', icon: <CheckIcon size={18} /> },
  VIDEO_SUSPENDED: { tone: 'tone-danger', icon: <FlagIcon size={18} /> },
  NEW_COMMENT: { tone: 'tone-brand', icon: <CommentIcon size={18} /> },
  COMMENT_REPLY: { tone: 'tone-brand', icon: <CommentIcon size={18} /> },
  NEW_FOLLOWER: { tone: 'tone-brand', icon: <UsersIcon size={18} /> },
  NEW_LIKE: { tone: 'tone-brand', icon: <HeartIcon size={18} filled /> },
};

/** Basic in-app notifications (brief section 20, Milestone 7). */
export function Notifications() {
  const queryClient = useQueryClient();

  const list = useInfiniteQuery({
    queryKey: ['notifications'],
    queryFn: ({ pageParam }: { pageParam: string | null }) => getNotifications(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    refetchInterval: 10_000,
  });

  const markRead = useMutation({
    mutationFn: markNotificationRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });

  const markAll = useMutation({
    mutationFn: markAllNotificationsRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });

  const items: NotificationItem[] = list.data?.pages.flatMap((p) => p.items) ?? [];
  // Account-wide, not just this page: an inbox with a long history under-reported
  // the badge when it counted only the rows on screen.
  const unreadCount = list.data?.pages[0]?.unreadCount ?? 0;

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
      {unreadCount > 0 && (
        <div className="sheet-toolbar">
          <span className="sheet-toolbar-label">
            {unreadCount} unread {unreadCount === 1 ? 'notification' : 'notifications'}
          </span>
          <button className="btn-ghost btn-sm" disabled={markAll.isPending} onClick={() => markAll.mutate()}>
            {markAll.isPending ? 'Clearing…' : 'Mark all read'}
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
                {/* `relatedVideoId` was fetched, validated and typed all along,
                    but never rendered -- so "someone commented on your video"
                    was a dead end with no way to reach the video it named.
                    Opening it also marks the notification read, since having
                    looked at the thing is what "read" means. */}
                {n.relatedVideoId ? (
                  <button
                    type="button"
                    className="notif-message notif-message-link"
                    onClick={() => {
                      if (!n.read) markRead.mutate(n.notificationId);
                      navigate(videoPath(n.relatedVideoId as string));
                    }}
                  >
                    {n.message}
                  </button>
                ) : (
                  <div className="notif-message">{n.message}</div>
                )}
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

      {list.hasNextPage && (
        <button
          className="btn-ghost btn-sm comment-more"
          disabled={list.isFetchingNextPage}
          onClick={() => void list.fetchNextPage()}
        >
          {list.isFetchingNextPage ? 'Loading…' : 'Load older'}
        </button>
      )}
    </div>
  );
}
