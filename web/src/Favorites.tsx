import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import {
  type FavoriteCollection,
  type SavedVideo,
  createCollection,
  createPublicSession,
  deleteCollection,
  getCollection,
  getCollections,
  getCollectionsForVideo,
  renameCollection,
  saveVideo,
  unsaveVideo,
} from './api';
import { Sheet } from './App';
import {
  BookmarkIcon,
  CheckIcon,
  ChevronRightIcon,
  FolderIcon,
  PencilIcon,
  PlayIcon,
  PlusIcon,
  TrashIcon,
} from './icons';
import { attachHls, detachHls } from './Upload';
import { Avatar, avatarHue, handleFor, relativeTime } from './ui';

/**
 * Favorite collections: the viewer's own folders of saved videos.
 *
 * <p>Two screens in one panel rather than two panels — a list of collections,
 * and one collection's videos — because opening a folder and coming back is a
 * single navigation, and stacking a second Sheet over the first would put two
 * scrims between the viewer and the video they were watching.
 */
export function Favorites() {
  const [openId, setOpenId] = useState<string | null>(null);

  return openId ? (
    <CollectionDetail collectionId={openId} onBack={() => setOpenId(null)} />
  ) : (
    <CollectionList onOpen={setOpenId} />
  );
}

function CollectionList({ onOpen }: { onOpen: (collectionId: string) => void }) {
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const list = useQuery({ queryKey: ['collections'], queryFn: getCollections });

  const create = useMutation({
    mutationFn: () => createCollection(name),
    onSuccess: () => {
      setName('');
      setCreating(false);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['collections'] });
    },
    onError: (err) => setError((err as Error).message),
  });

  if (list.isPending) return <CollectionSkeleton />;
  if (list.isError) return <div className="status-line is-error">{(list.error as Error).message}</div>;

  return (
    <div className="collections">
      {creating ? (
        <form
          className="collection-new"
          onSubmit={(e) => {
            e.preventDefault();
            if (name.trim() && !create.isPending) create.mutate();
          }}
        >
          <input
            autoFocus
            value={name}
            maxLength={60}
            placeholder="Collection name"
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                e.stopPropagation();
                setCreating(false);
                setError(null);
              }
            }}
          />
          <button type="submit" className="btn-primary" disabled={!name.trim() || create.isPending}>
            Create
          </button>
        </form>
      ) : (
        <button type="button" className="collection-add" onClick={() => setCreating(true)}>
          <span className="collection-add-glyph">
            <PlusIcon size={18} />
          </span>
          New collection
        </button>
      )}

      {error && <div className="status-line is-error">{error}</div>}

      {list.data.length === 0 && !creating && (
        <div className="empty">
          <BookmarkIcon />
          <span className="empty-text">
            Nothing saved yet. Tap the bookmark on a video to file it in a collection.
          </span>
        </div>
      )}

      <ul className="collection-list">
        {list.data.map((collection) => (
          <li key={collection.collectionId}>
            <CollectionRow collection={collection} onOpen={() => onOpen(collection.collectionId)} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function CollectionRow({ collection, onOpen }: { collection: FavoriteCollection; onOpen: () => void }) {
  const queryClient = useQueryClient();
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState(collection.name);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['collections'] });

  const rename = useMutation({
    mutationFn: () => renameCollection(collection.collectionId, name),
    onSuccess: () => {
      setRenaming(false);
      setError(null);
      void invalidate();
    },
    onError: (err) => setError((err as Error).message),
  });

  const remove = useMutation({
    mutationFn: () => deleteCollection(collection.collectionId),
    onSuccess: () => void invalidate(),
    onError: (err) => setError((err as Error).message),
  });

  if (renaming) {
    return (
      <form
        className="collection-new"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim() && !rename.isPending) rename.mutate();
        }}
      >
        <input
          autoFocus
          value={name}
          maxLength={60}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              e.stopPropagation();
              setRenaming(false);
              setName(collection.name);
            }
          }}
        />
        <button type="submit" className="btn-primary" disabled={!name.trim() || rename.isPending}>
          Save
        </button>
      </form>
    );
  }

  return (
    <div className="collection-row">
      <button type="button" className="collection-open" onClick={onOpen}>
        <span className="collection-glyph" style={{ '--h': avatarHue(collection.collectionId) } as React.CSSProperties}>
          <FolderIcon size={18} />
        </span>
        <span className="collection-text">
          <span className="collection-name">{collection.name}</span>
          <span className="collection-meta">
            {collection.itemCount} {collection.itemCount === 1 ? 'video' : 'videos'} · updated{' '}
            {relativeTime(collection.updatedAt)}
          </span>
        </span>
        <ChevronRightIcon className="collection-cue" size={16} />
      </button>

      <div className="collection-actions">
        <button type="button" className="icon-btn" onClick={() => setRenaming(true)} aria-label={`Rename ${collection.name}`}>
          <PencilIcon size={16} />
        </button>
        {/* Deleting throws away a list the viewer built by hand, and the rows
            underneath shift the moment it happens -- so it asks first. */}
        {confirmDelete ? (
          <button
            type="button"
            className="btn-danger-ghost btn-sm"
            onClick={() => remove.mutate()}
            disabled={remove.isPending}
          >
            Delete?
          </button>
        ) : (
          <button
            type="button"
            className="icon-btn"
            onClick={() => setConfirmDelete(true)}
            aria-label={`Delete ${collection.name}`}
          >
            <TrashIcon size={16} />
          </button>
        )}
      </div>

      {error && <div className="status-line is-error">{error}</div>}
    </div>
  );
}

function CollectionDetail({ collectionId, onBack }: { collectionId: string; onBack: () => void }) {
  const queryClient = useQueryClient();
  const [openItem, setOpenItem] = useState<SavedVideo | null>(null);

  const detail = useQuery({ queryKey: ['collection', collectionId], queryFn: () => getCollection(collectionId) });

  const remove = useMutation({
    mutationFn: (videoId: string) => unsaveVideo(collectionId, videoId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['collection', collectionId] });
      void queryClient.invalidateQueries({ queryKey: ['collections'] });
      void queryClient.invalidateQueries({ queryKey: ['saved'] });
    },
  });

  return (
    <div>
      <button type="button" className="collection-back" onClick={onBack}>
        <ChevronRightIcon size={16} className="collection-back-cue" />
        All collections
      </button>

      {detail.isPending && <CollectionSkeleton />}
      {detail.isError && <div className="status-line is-error">{(detail.error as Error).message}</div>}

      {detail.data && (
        <>
          <div className="collection-head">
            <h3>{detail.data.collection.name}</h3>
            <span className="collection-meta">
              {detail.data.items.length} {detail.data.items.length === 1 ? 'video' : 'videos'}
            </span>
          </div>

          {/* Saved rows whose video has since been taken down. Counted rather
              than listed: a row that cannot play is worse than a note. */}
          {detail.data.unavailableCount > 0 && (
            <div className="status-line">
              {detail.data.unavailableCount} saved{' '}
              {detail.data.unavailableCount === 1 ? 'video is' : 'videos are'} no longer available.
            </div>
          )}

          {detail.data.items.length === 0 && detail.data.unavailableCount === 0 && (
            <div className="empty">
              <BookmarkIcon />
              <span className="empty-text">This collection is empty.</span>
            </div>
          )}

          <ul className="search-list">
            {detail.data.items.map((item) => (
              <li key={item.videoId} className="saved-row">
                <button type="button" className="search-hit" onClick={() => setOpenItem(item)}>
                  <span
                    className="search-thumb"
                    style={{ '--h': avatarHue(item.videoId) } as React.CSSProperties}
                    aria-hidden="true"
                  >
                    <PlayIcon size={16} />
                  </span>
                  <div className="search-hit-text">
                    <div className="search-hit-name">{item.title || 'Untitled video'}</div>
                    <div className="search-hit-sub">
                      {item.creatorDisplayName} · saved {relativeTime(item.savedAt)}
                    </div>
                    {item.description && <div className="search-hit-desc">{item.description}</div>}
                  </div>
                </button>
                <button
                  type="button"
                  className="icon-btn saved-remove"
                  onClick={() => remove.mutate(item.videoId)}
                  disabled={remove.isPending}
                  aria-label={`Remove ${item.title || 'video'} from ${detail.data.collection.name}`}
                >
                  <TrashIcon size={16} />
                </button>
              </li>
            ))}
          </ul>
        </>
      )}

      {openItem && (
        <Sheet title={openItem.title || 'Video'} onClose={() => setOpenItem(null)}>
          <SavedVideoPlayer item={openItem} />
        </Sheet>
      )}
    </div>
  );
}

function SavedVideoPlayer({ item }: { item: SavedVideo }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [orientation, setOrientation] = useState<'portrait' | 'landscape' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const session = useMutation({
    mutationFn: () => createPublicSession(item.videoId),
    onSuccess: (result) => {
      setError(null);
      attachHls(videoRef.current, item.videoId, result.processingVersion, setError);
      videoRef.current?.play().catch(() => {});
    },
    onError: (err) => setError((err as Error).message),
  });

  useEffect(() => {
    session.mutate();
    return () => detachHls(videoRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.videoId]);

  return (
    <div>
      <video
        ref={videoRef}
        controls
        autoPlay
        playsInline
        className={`preview-video${orientation === 'landscape' ? ' preview-video-landscape' : ''}`}
        style={{ margin: '0 auto' }}
        onLoadedMetadata={(e) => {
          const { videoWidth, videoHeight } = e.currentTarget;
          if (videoWidth && videoHeight) setOrientation(videoWidth >= videoHeight ? 'landscape' : 'portrait');
        }}
      />
      <div className="upload-meta" style={{ marginTop: '0.85rem' }}>
        <Avatar seed={item.creatorId} label={item.creatorDisplayName} size="sm" />
        <div className="search-hit-text">
          <div className="search-hit-name">{item.creatorDisplayName}</div>
          <div className="search-hit-sub">{handleFor(item.creatorId)}</div>
        </div>
      </div>
      {item.description && <p className="search-hit-desc is-full">{item.description}</p>}
      {error && <div className="status-line is-error">{error}</div>}
    </div>
  );
}

function CollectionSkeleton() {
  return (
    <ul className="search-list" aria-hidden="true">
      {[0, 1, 2].map((i) => (
        <li key={i} className="search-skeleton-row">
          <span className="search-skeleton-thumb" style={{ height: 42 }} />
          <span className="search-skeleton-lines">
            <span className="search-skeleton-line" />
            <span className="search-skeleton-line is-short" />
          </span>
        </li>
      ))}
    </ul>
  );
}

/**
 * The save picker, opened from the feed's bookmark button.
 *
 * <p>Every collection is listed with its current membership so one sheet both
 * files and un-files the video — the same button that saved it removes it,
 * which is the only way "saved" stays a state the viewer can undo where they
 * set it.
 */
export function SaveToCollection({ videoId, onClose }: { videoId: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const list = useQuery({
    queryKey: ['collections', videoId],
    queryFn: () => getCollectionsForVideo(videoId),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['collections'] });
    void queryClient.invalidateQueries({ queryKey: ['saved', videoId] });
  };

  const toggle = useMutation({
    mutationFn: async (collection: FavoriteCollection) => {
      if (collection.containsVideo) await unsaveVideo(collection.collectionId, videoId);
      else await saveVideo(videoId, collection.collectionId);
    },
    onSuccess: () => {
      setError(null);
      invalidate();
    },
    onError: (err) => setError((err as Error).message),
  });

  const createAndSave = useMutation({
    mutationFn: async () => {
      const collection = await createCollection(name);
      await saveVideo(videoId, collection.collectionId);
    },
    onSuccess: () => {
      setName('');
      setCreating(false);
      setError(null);
      invalidate();
    },
    onError: (err) => setError((err as Error).message),
  });

  return (
    <div>
      {creating ? (
        <form
          className="collection-new"
          onSubmit={(e) => {
            e.preventDefault();
            if (name.trim() && !createAndSave.isPending) createAndSave.mutate();
          }}
        >
          <input
            autoFocus
            value={name}
            maxLength={60}
            placeholder="Collection name"
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                e.stopPropagation();
                setCreating(false);
              }
            }}
          />
          <button type="submit" className="btn-primary" disabled={!name.trim() || createAndSave.isPending}>
            Create &amp; save
          </button>
        </form>
      ) : (
        <button type="button" className="collection-add" onClick={() => setCreating(true)}>
          <span className="collection-add-glyph">
            <PlusIcon size={18} />
          </span>
          New collection
        </button>
      )}

      {error && <div className="status-line is-error">{error}</div>}

      {list.isPending && <CollectionSkeleton />}
      {list.isError && <div className="status-line is-error">{(list.error as Error).message}</div>}

      {list.data && (
        <ul className="collection-list">
          {list.data.map((collection) => (
            <li key={collection.collectionId}>
              <button
                type="button"
                className={`collection-pick${collection.containsVideo ? ' is-in' : ''}`}
                onClick={() => toggle.mutate(collection)}
                disabled={toggle.isPending}
                aria-pressed={collection.containsVideo}
              >
                <span
                  className="collection-glyph"
                  style={{ '--h': avatarHue(collection.collectionId) } as React.CSSProperties}
                >
                  <FolderIcon size={18} />
                </span>
                <span className="collection-text">
                  <span className="collection-name">{collection.name}</span>
                  <span className="collection-meta">
                    {collection.itemCount} {collection.itemCount === 1 ? 'video' : 'videos'}
                  </span>
                </span>
                <span className="collection-check">{collection.containsVideo && <CheckIcon size={15} />}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {list.data?.length === 0 && !creating && (
        <div className="empty">
          <FolderIcon />
          <span className="empty-text">No collections yet — make one to file this video.</span>
        </div>
      )}

      <div className="btn-row" style={{ marginTop: '1rem', justifyContent: 'flex-end' }}>
        <button type="button" className="btn-ghost" onClick={onClose}>
          Done
        </button>
      </div>
    </div>
  );
}
