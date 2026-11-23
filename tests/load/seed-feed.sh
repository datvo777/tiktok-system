#!/usr/bin/env bash
# Fills the scratch database with eligible videos for the feed benchmark, straight into the tables the feed
# reads (eligibility projection + social counts). The upload -> transcode -> moderation -> publication
# pipeline is not what is being measured, and 300 real uploads would take far longer than the benchmark.
#
#   tests/load/seed-feed.sh A|B|C [videos=400] [creators=60]
#
# Profiles size what a cached ranking carries (docs: the plan's C1), and so how much Redis memory a viewer costs:
#   A  title 40 chars, description 150, ASCII
#   B  the same lengths, Vietnamese with diacritics (2-3 bytes per letter in UTF-8)
#   C  title 150, description 2000: both columns full, the worst case
# Re-running replaces the previous bench rows, so switching profile is just running it again.
set -euo pipefail
profile="${1:?usage: seed-feed.sh A|B|C [videos] [creators]}"
videos="${2:-400}"
creators="${3:-60}"
PG_CONTAINER="${PG_CONTAINER:-sv-postgres}"
DB="${POSTGRES_DB:-short_video_bench}"

case "$profile" in
  A) unit='lorem ipsum dolor sit amet ';  tlen=40;  dlen=150 ;;
  B) unit='Tiếng Việt có dấu đầy đủ ';    tlen=40;  dlen=150 ;;
  C) unit='lorem ipsum dolor sit amet ';  tlen=150; dlen=2000 ;;
  *) echo "profile must be A, B or C" >&2; exit 2 ;;
esac

docker exec -i "$PG_CONTAINER" psql -U shortvideo -d "$DB" -v ON_ERROR_STOP=1 -v unit="$unit" -v tlen="$tlen" -v dlen="$dlen" \
  -v videos="$videos" -v creators="$creators" <<'SQL'
BEGIN;
-- Bench rows are recognisable by a fixed first character of the id; nothing else uses it.
DELETE FROM social.video_like    WHERE video_id::text LIKE 'bbbbbbbb-%';
DELETE FROM social.comment       WHERE video_id::text LIKE 'bbbbbbbb-%';
DELETE FROM social.video_engagement WHERE video_id::text LIKE 'bbbbbbbb-%';
DELETE FROM eligibility.video_eligibility   WHERE video_id   LIKE 'bbbbbbbb-%';
DELETE FROM eligibility.account_eligibility WHERE account_id LIKE 'cccccccc-%';

INSERT INTO eligibility.account_eligibility (account_id, account_state, is_account_eligible, source_version, updated_at)
SELECT 'cccccccc-0000-4000-8000-' || lpad(g::text, 12, '0'), 'ACTIVE', true, 1, now()
FROM generate_series(1, :creators) g;

-- Newest first by g, a few minutes apart, so freshness differs between videos and the ranking has a shape.
INSERT INTO eligibility.video_eligibility (
    video_id, creator_id, processing_state, durability_state, asset_lifecycle_state, legal_serving_state,
    moderation_state, publication_state, publication_intent_requested, is_video_eligible,
    processing_version_source, moderation_version_source, publication_version_source,
    title, description, updated_at)
SELECT 'bbbbbbbb-0000-4000-8000-' || lpad(g::text, 12, '0'),
       'cccccccc-0000-4000-8000-' || lpad((1 + g % :creators)::text, 12, '0'),
       'READY', 'DURABLE', 'ACTIVE', 'CLEAR', 'APPROVED', 'PUBLISHED', true, true, 1, 1, 1,
       left(repeat(:'unit' || g || ' ', 40), :tlen),
       left(repeat(:'unit' || g || ' ', 400), :dlen),
       now() - (g * interval '7 minutes')
FROM generate_series(1, :videos) g;

-- Engagement with a long tail, so the scores differ: a few videos draw most of the likes.
INSERT INTO social.video_like (video_id, account_id, created_at)
SELECT ('bbbbbbbb-0000-4000-8000-' || lpad(v::text, 12, '0'))::uuid, gen_random_uuid(), now()
FROM generate_series(1, :videos) v, LATERAL generate_series(1, (200.0 / v)::int + (random() * 3)::int) l;
INSERT INTO social.comment (comment_id, video_id, account_id, body, created_at)
SELECT gen_random_uuid(), ('bbbbbbbb-0000-4000-8000-' || lpad(v::text, 12, '0'))::uuid, gen_random_uuid(), 'bench', now()
FROM generate_series(1, :videos) v, LATERAL generate_series(1, (40.0 / v)::int + (random() * 2)::int) l;
-- The feed ranks on the maintained totals, not on the rows above; the migration's backfill ran before these
-- rows existed, so without this every seeded video would score zero engagement and the ranking would be flat.
INSERT INTO social.video_engagement (video_id, like_count, comment_count)
SELECT COALESCE(l.video_id, c.video_id), COALESCE(l.n, 0), COALESCE(c.n, 0)
FROM (SELECT video_id, count(*) AS n FROM social.video_like WHERE video_id::text LIKE 'bbbbbbbb-%' GROUP BY video_id) l
FULL OUTER JOIN (
    SELECT video_id, count(*) AS n FROM social.comment WHERE deleted_at IS NULL AND video_id::text LIKE 'bbbbbbbb-%' GROUP BY video_id
) c ON c.video_id = l.video_id;
COMMIT;
SELECT count(*) AS videos,
       round(avg(octet_length(title)))       AS avg_title_bytes,
       round(avg(octet_length(description))) AS avg_description_bytes
FROM eligibility.video_eligibility WHERE video_id LIKE 'bbbbbbbb-%';
SQL
