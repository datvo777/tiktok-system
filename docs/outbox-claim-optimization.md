# Tối ưu câu claim của outbox relay và thêm cảnh báo backlog

Tổng kết công việc thực hiện theo plan "tối ưu câu claim của outbox relay khi backlog lớn".
Chưa commit gì.

## 1. Vấn đề

`OutboxRepository.CLAIM` chọn các dòng cần gửi bằng một câu có `WHERE (status IN (...) OR (status='CLAIMED' AND claimed_until < now())) AND available_at <= now() ORDER BY occurred_at, aggregate_id, aggregate_version LIMIT n`.

Index cũ `outbox_event_claimable_idx` có cột đầu là `available_at`, không khớp `ORDER BY`, và nhánh `OR` chặn việc dừng sớm theo `LIMIT`. Vì vậy mỗi lần claim phải đọc và sort **toàn bộ** tập dòng claimable, nên chi phí tăng theo backlog chứ không theo `LIMIT`.

## 1b. Câu query trước và sau

Phần `UPDATE ... FROM selected ... RETURNING` giữ nguyên ở cả hai bản; chỉ phần chọn dòng (`selected`) thay đổi. Dấu `?` là tham số JDBC.

### Trước (commit `5da15ae`)

```sql
WITH selected AS (
    SELECT event_id
    FROM platform.outbox_event
    WHERE (
        status IN ('PENDING', 'RETRY')
        OR (status = 'CLAIMED' AND claimed_until < now())
    )
      AND available_at <= now()
    ORDER BY occurred_at, aggregate_id, aggregate_version
    FOR UPDATE SKIP LOCKED
    LIMIT ?                                   -- batchSize
)
UPDATE platform.outbox_event o
SET status = 'CLAIMED',
    claimed_by = ?,                           -- relayId
    claim_token = ?,
    claimed_until = now() + make_interval(secs => ?),
    attempt_count = attempt_count + 1,
    last_attempt_at = now()
FROM selected
WHERE o.event_id = selected.event_id
RETURNING o.event_id, o.aggregate_type, o.aggregate_id, o.event_type,
          o.schema_version, o.aggregate_version, o.payload::text AS payload,
          o.occurred_at, o.attempt_count, o.claim_token
```

Index phục vụ: `outbox_event_claimable_idx (available_at, occurred_at, aggregate_id, aggregate_version) WHERE status IN ('PENDING','RETRY','CLAIMED')`.

Plan thực tế ở backlog 200k: `Bitmap Heap Scan` đọc 200.000 dòng, rồi `Sort` (external merge, 12 MB ra đĩa), rồi `LockRows`, rồi `Limit 100`. Thời gian ~**49 ms**, tăng theo backlog.

### Sau (hiện tại)

```sql
WITH candidates AS (
    (SELECT event_id, occurred_at, aggregate_id, aggregate_version
     FROM platform.outbox_event
     WHERE status IN ('PENDING', 'RETRY') AND available_at <= now()
     ORDER BY occurred_at, aggregate_id, aggregate_version
     LIMIT ?)                                 -- batchSize
    UNION ALL
    (SELECT event_id, occurred_at, aggregate_id, aggregate_version
     FROM platform.outbox_event
     WHERE status = 'CLAIMED' AND claimed_until < now() AND available_at <= now()
     ORDER BY occurred_at, aggregate_id, aggregate_version
     LIMIT ?)                                 -- batchSize
),
top AS (
    SELECT event_id
    FROM candidates
    ORDER BY occurred_at, aggregate_id, aggregate_version
    LIMIT ?                                   -- batchSize
),
selected AS (
    SELECT o.event_id
    FROM platform.outbox_event o
    JOIN top USING (event_id)
    WHERE (
        o.status IN ('PENDING', 'RETRY')
        OR (o.status = 'CLAIMED' AND o.claimed_until < now())
    )
      AND o.available_at <= now()
    FOR UPDATE OF o SKIP LOCKED
)
UPDATE platform.outbox_event o
SET status = 'CLAIMED',
    claimed_by = ?,                           -- relayId
    claim_token = ?,
    claimed_until = now() + make_interval(secs => ?),
    attempt_count = attempt_count + 1,
    last_attempt_at = now()
FROM selected
WHERE o.event_id = selected.event_id
RETURNING o.event_id, o.aggregate_type, o.aggregate_id, o.event_type,
          o.schema_version, o.aggregate_version, o.payload::text AS payload,
          o.occurred_at, o.attempt_count, o.claim_token
```

Index phục vụ (V49):

```sql
CREATE INDEX outbox_event_ready_idx
    ON platform.outbox_event (occurred_at, aggregate_id, aggregate_version)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX outbox_event_reclaim_idx
    ON platform.outbox_event (occurred_at, aggregate_id, aggregate_version)
    WHERE status = 'CLAIMED';
```

Plan thực tế ở backlog 200k: `Merge Append` của hai `Index Scan` có `Limit`, dừng sau 100 dòng; rồi 100 lần tra theo khoá chính để khoá dòng. Thời gian ~**0,64 ms**, không tăng theo backlog (trừ vùng xám ở mục 4).

### Khác biệt chính

| | Trước | Sau |
|---|---|---|
| Cách gộp hai nguồn | một `WHERE ... OR ...` | `UNION ALL` hai nhánh, mỗi nhánh một index |
| Đọc theo thứ tự `ORDER BY` | không (sort cả tập) | có (index đúng thứ tự, dừng ở `LIMIT`) |
| Khoá dòng | cùng bước chọn | bước riêng sau khi chọn (`FOR UPDATE` không dùng được trên `UNION`) |
| Lọc lại trạng thái khi khoá | không cần | bắt buộc, để không claim trùng dòng vừa bị relay khác lấy |
| `ORDER BY` ở bước khoá | có | bỏ (`SKIP LOCKED` không chờ; Java sort lại batch) |
| Số tham số `LIMIT` | 1 | 3 (cùng `batchSize`) |

`buryAbandoned` không đổi câu lệnh, nhưng sau V49 nó dùng `outbox_event_reclaim_idx` thay vì quét 200k mục của index cũ (21,7 ms xuống 0,07 ms).

## 2. Đã làm gì, và tại sao

### 2.1. Đo trước khi sửa (Postgres 17.4, container tạm)

Plan yêu cầu "đo trước, sửa sau", và các con số trong plan là từ Postgres 16 với dữ liệu giả. Tôi đo lại trên đúng phiên bản dự án (1M dòng `PUBLISHED` + 200k dòng chờ):

| | Plan | Đo thực tế |
|---|---|---|
| Claim cũ, backlog 200k | ~410 ms | **~49 ms** (bitmap scan + sort ra đĩa 12 MB) |
| Claim hai nhánh (có `FOR UPDATE SKIP LOCKED`) | ~0,1 ms | **~0,64 ms** (cũng đúng với generic plan của JDBC) |
| `buryAbandoned`, backlog 200k | cần index riêng | **21,7 ms → 0,07 ms** |

Kết luận từ số đo:
- Giả thuyết H1 (chi phí tăng theo backlog) **đúng**.
- Giả thuyết H2 (claim chậm làm tụt tốc độ xả xuống ~250 dòng/s) **không đúng**: 49 ms mỗi 100 dòng vẫn cho khoảng 2.000 dòng/s, gần tốc độ gửi. Vấn đề có thật nhưng ở mức "đáng sửa", không cấp bách.
- Con số 410 ms của plan không tái hiện được.

### 2.2. Migration V49: hai index đúng thứ tự `ORDER BY`

File: `backend/shared/outbox/src/main/resources/db/migration/platform/V49__platform_outbox_claim_order_indexes.sql`

- `outbox_event_ready_idx (occurred_at, aggregate_id, aggregate_version) WHERE status IN ('PENDING','RETRY')`
- `outbox_event_reclaim_idx (occurred_at, aggregate_id, aggregate_version) WHERE status = 'CLAIMED'`

Tại sao hai index: claim có hai nguồn không liên quan nhau (dòng sẵn sàng và dòng hết lease). Gộp bằng `OR` thì planner không đọc được cái nào theo thứ tự. Tách ra, mỗi nhánh đọc theo index và dừng ở `LIMIT`.

V49 cũng `DROP INDEX outbox_event_claimable_idx` (xem mục 4).

Số phiên bản V49 vì V48 là số lớn nhất trong toàn repo. Không dùng `CONCURRENTLY` (khi tạo hoặc bỏ index) vì Flyway chạy migration trong transaction và bảng outbox nhỏ ở MVP (đã ghi chú trong file).

### 2.3. Viết lại `CLAIM` thành 3 bước

File: `backend/shared/outbox/src/main/java/com/shortvideo/shared/outbox/OutboxRepository.java`

1. `candidates`: hai nhánh `UNION ALL`, mỗi nhánh lấy `n` dòng đầu theo index.
2. `top`: giữ `n` dòng đầu của chúng.
3. `selected`: khoá dòng bằng `FOR UPDATE OF o SKIP LOCKED` và **lọc lại điều kiện trạng thái**.

Tại sao ba bước: Postgres không cho `FOR UPDATE` trực tiếp trên `UNION`, nên phải chọn trước rồi khoá sau.

Tại sao phải lọc lại trạng thái trong `selected`: giữa lúc chọn và lúc khoá, relay khác có thể đã claim dòng đó. Tôi **thử phá cố ý** (bỏ bước lọc lại) thì test đồng thời đỏ ở 2/3 lần chạy (có claim trùng), nên cảnh báo của plan là thật. Test này là race nên không đỏ 100% khi code sai.

Đã bỏ `ORDER BY` ở bước `selected` vì `SKIP LOCKED` không bao giờ chờ nên không thể deadlock, và `OutboxRelay` vẫn sort lại batch trước khi gửi. Chữ ký `claimBatch` không đổi nên `OutboxRelay` không phải sửa.

Thay đổi ngữ nghĩa (đã ghi trong javadoc): nếu một số dòng đang bị relay khác khoá, claim trả **ít hơn `n`** dòng thay vì với tay sang dòng kế. Hiện chỉ có một relay nên không xảy ra.

### 2.4. Bỏ bước 3 của plan (index riêng cho `buryAbandoned`)

`outbox_event_reclaim_idx` chỉ chứa dòng `CLAIMED` nên `buryAbandoned` dùng được luôn. Đo cho thấy 21,7 ms → 0,07 ms mà không cần index thứ ba, nên tôi bỏ để khỏi tăng chi phí ghi.

### 2.5. Test mới: `OutboxClaimIT` (5 test)

File: `backend/app/src/test/java/com/shortvideo/app/OutboxClaimIT.java`

- Lấy dòng cũ nhất từ cả hai nguồn; bỏ qua dòng chưa đến hạn và dòng còn lease.
- Claim tăng `attempt_count` và đóng dấu lease.
- Cùng timestamp thì tie-break theo `aggregate_id` rồi `aggregate_version`.
- 4 luồng claim đồng thời không bao giờ lấy trùng một dòng.
- Xả lần lượt thì mỗi dòng được claim đúng một lần.

Dữ liệu test đặt ở năm 2000 để luôn đứng trước dữ liệu của test khác trong cùng DB và batch không với sang dòng không thuộc test.

### 2.6. Mục 7: quan sát backlog

- `OutboxBacklogGauge`: gauge `outbox.oldest.waiting.seconds` = tuổi (theo `occurred_at`) của event cũ nhất chưa gửi; 0 nếu không có.
- Tính các dòng `PENDING`, `RETRY` và `CLAIMED` đã hết lease (relay chết giữa batch thì chính những dòng này bị kẹt).
- Dùng tuổi thay vì số dòng vì chỉ cần đọc mục đầu của hai index (5 buffer ở backlog 200k, không tăng theo backlog), và "event trễ 5 phút" là thứ người vận hành hành động theo.
- Gauge luôn bật, kể cả khi relay tắt, vì lúc đó backlog mới tăng.
- Tách `CachedReading` (cache ngắn, giữ giá trị cũ khi DB lỗi) từ `OutboxDeadGauge` để hai gauge dùng chung thay vì sao chép.
- Alert `OutboxBacklogGrowing` trong `infrastructure/prometheus/alerts.yml`: `outbox_oldest_waiting_seconds > 300` trong 5 phút, mức warning. Ngưỡng 300 s khớp `max-backoff` mặc định và là lựa chọn của tôi, có thể chỉnh. Một event lỗi đơn lẻ cũng làm alert nổ cho đến khi thành `DEAD` rồi `OutboxEventsDead` tiếp quản.
- Test: `OutboxBacklogGaugeTest` (3 unit), `OutboxBacklogMetricIT` (2 IT).

## 3. Kết quả kiểm chứng

- 24 test outbox xanh (15 unit + 9 IT), IT chạy trên Postgres 17.4 qua Testcontainers, kể cả migration V49 qua Flyway.
- `promtool check rules`: 12 rule hợp lệ.
- Chưa chạy alert trên Prometheus thật; chưa chạy relay thật với backlog 200k để đo thời gian xả.

## 4. Rủi ro và lưu ý

- **Vùng xám của hai nhánh:** nếu hàng trăm nghìn dòng `RETRY` đang backoff nằm trước các dòng sẵn sàng (ví dụ Kafka chết lâu), nhánh mới phải lọc qua chúng. Đo với 150k dòng như vậy: 13,6 ms, vẫn nhanh hơn câu cũ (36 ms) nhưng không còn là hằng số.
- **Index cũ `outbox_event_claimable_idx`** đã được bỏ ngay trong V49 (`DROP INDEX`): không còn câu nào dùng nó (đã grep, chỉ `OutboxRepository` truy vấn theo thứ tự claim), và giữ lại chỉ tốn chi phí ghi. Plan ban đầu muốn bỏ ở migration riêng sau khi V49 đã chạy một thời gian; vì V49 chưa commit và chưa chạy ở đâu nên gộp luôn. Nếu DB dev nào đã chạy bản V49 cũ thì Flyway sẽ báo checksum không khớp, cần xoá DB đó hoặc `flyway repair`. Chưa chạy lại migration sau khi sửa.
- Nhiều relay: xem mục 2.3 (claim có thể trả ít hơn `n` dòng).

## 5. Các file thay đổi

Sửa: `OutboxRepository.java`, `OutboxDeadGauge.java`, `OutboxConfiguration.java`, `infrastructure/prometheus/alerts.yml`.

Mới: `V49__platform_outbox_claim_order_indexes.sql`, `CachedReading.java`, `OutboxBacklogGauge.java`, `OutboxBacklogGaugeTest.java`, `OutboxClaimIT.java`, `OutboxBacklogMetricIT.java`.
