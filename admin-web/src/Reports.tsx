import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import {
  listOpenReports,
  removeVideo,
  resolveReport,
  type OpenReport,
  type ReportResolution,
} from './api';
import { EntityId, ModeratorPlayer, useToast } from './ui';

/** How a reason enum reads to a person. */
const REASON_LABEL: Record<string, string> = {
  SEXUAL_CONTENT: 'Nudity or sexual content',
  VIOLENCE_OR_GORE: 'Violence or graphic content',
  HATE_OR_HARASSMENT: 'Hate speech or harassment',
  DANGEROUS_ACTS: 'Dangerous acts',
  MISINFORMATION: 'Harmful misinformation',
  SPAM_OR_SCAM: 'Spam or scam',
  INTELLECTUAL_PROPERTY: 'Copyright or trademark',
  CHILD_SAFETY: 'Child safety',
  OTHER: 'Something else',
};

/**
 * The queue of viewer-submitted reports.
 *
 * <p>Ordered oldest-first by the server, but each row carries how many people
 * reported the same subject — that count, not the age, is what a moderator
 * actually triages on, so it is the loudest thing on the row.
 *
 * <p>Resolving a report and acting on the video are deliberately two separate
 * controls. A report can be dismissed without touching the video, and a video
 * can be removed for reasons no report raised; collapsing them would make the
 * audit trail unable to tell those apart.
 */
export function ReportsQueue() {
  const queryClient = useQueryClient();
  const pushToast = useToast();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [note, setNote] = useState('');

  const reports = useQuery({
    queryKey: ['open-reports'],
    queryFn: () => listOpenReports(),
    refetchInterval: 15000,
  });

  const items = useMemo(() => reports.data ?? [], [reports.data]);
  const current = items.find((r) => r.reportId === selectedId) ?? items[0] ?? null;

  function afterResolve(label: string) {
    return () => {
      pushToast('success', `${label}.`);
      setNote('');
      setSelectedId(null);
      void queryClient.invalidateQueries({ queryKey: ['open-reports'] });
    };
  }

  const resolve = useMutation({
    mutationFn: (resolution: ReportResolution) => resolveReport(current!.reportId, resolution, note),
    onSuccess: (_data, resolution) => afterResolve(`Report ${resolution.toLowerCase().replace('_', ' ')}`)(),
    onError: (error) => pushToast('error', `Could not resolve: ${(error as Error).message}`),
  });

  // Removing the video is the enforcement action; the report is closed as
  // ACTIONED in the same click, since that is what actioning it means.
  const removeAndAction = useMutation({
    mutationFn: async () => {
      await removeVideo(current!.subjectId, note.trim() || `Reported: ${current!.reason}`);
      await resolveReport(current!.reportId, 'ACTIONED', note);
    },
    onSuccess: afterResolve('Video removed and report actioned'),
    onError: (error) => pushToast('error', `Could not remove: ${(error as Error).message}`),
  });

  const busy = resolve.isPending || removeAndAction.isPending;

  return (
    <div className="review">
      <div className="review-main">
        <div className="review-head">
          <div className="qpos">
            QUEUE <b>reports</b>
          </div>
          <div className="qstats">
            <div className="qstat">
              Open<b>{items.length}</b>
            </div>
          </div>
        </div>

        {reports.isPending && (
          <div className="stage">
            <div className="stage-empty">Loading reports…</div>
          </div>
        )}

        {reports.isError && (
          <div className="stage">
            <div className="stage-empty">
              <h3>Could not load reports</h3>
              <p className="error-text">{(reports.error as Error).message}</p>
            </div>
          </div>
        )}

        {reports.isSuccess && !current && (
          <div className="stage">
            <div className="stage-empty">
              <h3>Nothing reported</h3>
              <p className="section-note">Reports from viewers arrive here.</p>
            </div>
          </div>
        )}

        {current && (
          <>
            {/* Only a video can be watched; an account or comment report is read. */}
            {current.subjectType === 'VIDEO' ? (
              <ModeratorPlayer videoId={current.subjectId} />
            ) : (
              <div className="stage">
                <div className="stage-empty">
                  <h3>{current.subjectType === 'ACCOUNT' ? 'Reported account' : 'Reported comment'}</h3>
                  <EntityId value={current.subjectId} />
                </div>
              </div>
            )}

            <section className="panel">
              <div className="panel-body">
                <p className="section-note">
                  Reported as <b>{REASON_LABEL[current.reason] ?? current.reason}</b>
                  {current.openReportsForSubject > 1 && (
                    <> · reported by {current.openReportsForSubject} people</>
                  )}
                </p>
                {current.detail && <blockquote className="report-detail">{current.detail}</blockquote>}

                <label className="field">
                  <span className="field-label">Note (recorded in the audit trail)</span>
                  <textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
                </label>

                <div className="btn-row">
                  <button
                    className="btn-danger"
                    disabled={busy || current.subjectType !== 'VIDEO'}
                    onClick={() => removeAndAction.mutate()}
                  >
                    {removeAndAction.isPending ? 'Removing…' : 'Remove video & action'}
                  </button>
                  <button disabled={busy} onClick={() => resolve.mutate('ACTIONED')}>
                    Actioned elsewhere
                  </button>
                  <button disabled={busy} onClick={() => resolve.mutate('DISMISSED')}>
                    Dismiss
                  </button>
                  <button disabled={busy} onClick={() => resolve.mutate('ABUSIVE_REPORT')}>
                    Abusive report
                  </button>
                </div>
              </div>
            </section>
          </>
        )}
      </div>

      <aside className="review-side">
        <section className="panel">
          <div className="panel-head">Open reports</div>
          <div className="panel-body">
            {items.length === 0 && <p className="section-note">Nothing waiting.</p>}
            <ul className="report-list">
              {items.map((report) => (
                <li key={report.reportId}>
                  <button
                    className={`report-row${report.reportId === current?.reportId ? ' is-active' : ''}`}
                    onClick={() => setSelectedId(report.reportId)}
                  >
                    <span className="report-row-reason">
                      {REASON_LABEL[report.reason] ?? report.reason}
                    </span>
                    <span className="report-row-meta">
                      {report.subjectType.toLowerCase()}
                      {report.openReportsForSubject > 1 && ` · ${report.openReportsForSubject}×`}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </section>
      </aside>
    </div>
  );
}

export type { OpenReport };
