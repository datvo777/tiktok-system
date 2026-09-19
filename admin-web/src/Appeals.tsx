import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { approveAppeal, denyAppeal, listPendingAppeals } from './api';
import { EntityId, ModeratorPlayer, useToast } from './ui';

/**
 * An appeal is the same decision shape as a first-pass review — watch the
 * video, decide, record why — so it lives on the Review surface as a second
 * queue rather than as an unrelated card somewhere else.
 *
 * It does *not* reuse the undo window from the moderation queue, deliberately.
 * That window exists to make a high-speed keyboard path safe; an appeal
 * requires a typed rationale, which already makes the decision deliberate and
 * slow. Adding a countdown there would delay a decision nobody made by reflex.
 */
export function AppealsQueue() {
  const queryClient = useQueryClient();
  const pushToast = useToast();

  const [index, setIndex] = useState(0);
  const [decisionReason, setDecisionReason] = useState('');

  const appeals = useQuery({
    queryKey: ['pending-appeals'],
    queryFn: listPendingAppeals,
    refetchInterval: 15000,
  });

  const items = useMemo(() => appeals.data ?? [], [appeals.data]);
  const current = items[Math.min(index, Math.max(0, items.length - 1))] ?? null;

  function afterDecision(label: string) {
    return () => {
      pushToast('success', `${label}.`);
      setDecisionReason('');
      setIndex(0);
      void queryClient.invalidateQueries({ queryKey: ['pending-appeals'] });
      void queryClient.invalidateQueries({ queryKey: ['queue-health'] });
    };
  }

  const approveMutation = useMutation({
    mutationFn: () => approveAppeal(current!.videoId, decisionReason.trim()),
    onSuccess: afterDecision('Appeal approved'),
    onError: (error) => pushToast('error', `Approve appeal failed: ${(error as Error).message}`),
  });

  const denyMutation = useMutation({
    mutationFn: () => denyAppeal(current!.videoId, decisionReason.trim()),
    onSuccess: afterDecision('Appeal denied'),
    onError: (error) => pushToast('error', `Deny appeal failed: ${(error as Error).message}`),
  });

  const busy = approveMutation.isPending || denyMutation.isPending;
  const canDecide = current !== null && decisionReason.trim().length > 0 && !busy;

  return (
    <div className="review">
      <div className="review-main">
        <div className="review-head">
          <div className="qpos">
            QUEUE <b>appeals</b>
            {current && (
              <>
                {' '}
                · item <b>{Math.min(index + 1, items.length)}</b> of <b>{items.length}</b>
              </>
            )}
          </div>
          <div className="qstats">
            <div className="qstat">
              Awaiting<b>{items.length}</b>
            </div>
          </div>
        </div>

        {appeals.isPending && <div className="stage"><div className="stage-empty">Loading appeals…</div></div>}

        {appeals.isError && (
          <div className="stage">
            <div className="stage-empty">
              <h3>Could not load appeals</h3>
              <p className="error-text">{(appeals.error as Error).message}</p>
            </div>
          </div>
        )}

        {appeals.isSuccess && !current && (
          <div className="stage">
            <div className="stage-empty">
              <h3>No appeals awaiting review</h3>
              <p>Creators can appeal a rejection; those land here.</p>
            </div>
          </div>
        )}

        {current && (
          <>
            <div className="stage">
              <ModeratorPlayer videoId={current.videoId} />
            </div>

            <div className="context">
              <div className="ctx" style={{ flexBasis: '100%' }}>
                <span className="k">What the creator says</span>
                <span className="v" style={{ whiteSpace: 'normal' }}>
                  {current.reason || <span className="is-dim">No rationale given</span>}
                </span>
              </div>
              <div className="ctx">
                <span className="k">Appeal state</span>
                <span className="v">{current.state}</span>
              </div>
              <div className="ctx">
                <span className="k">Video</span>
                <span className="v"><EntityId value={current.videoId} /></span>
              </div>
            </div>
          </>
        )}
      </div>

      <div className="rail">
        <div className="rail-sec">
          <label className="field">
            <span className="field-label">Decision reason — required, shown to the creator</span>
            <input
              value={decisionReason}
              maxLength={200}
              placeholder="Why this appeal succeeds or fails"
              disabled={!current}
              onChange={(e) => setDecisionReason(e.target.value)}
            />
          </label>
        </div>

        <div className="rail-sec">
          <span className="rail-label">Decision</span>
          <div className="decide">
            <button className="btn-ok" disabled={!canDecide} onClick={() => approveMutation.mutate()}>
              Uphold appeal
            </button>
            <button className="btn-crit" disabled={!canDecide} onClick={() => denyMutation.mutate()}>
              Deny
            </button>
          </div>
          {current && !decisionReason.trim() && (
            <p className="section-note">A rationale is required — it is what the creator is shown.</p>
          )}
        </div>

        {items.length > 1 && (
          <div className="rail-sec">
            <span className="rail-label">Queue</span>
            <div className="btn-row">
              <button disabled={index === 0} onClick={() => setIndex((i) => Math.max(0, i - 1))}>
                ← Previous
              </button>
              <button
                disabled={index >= items.length - 1}
                onClick={() => setIndex((i) => Math.min(items.length - 1, i + 1))}
              >
                Next →
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
