# Relay outbox bị nghẽn từng đợt: nguyên nhân, cách sửa, vì sao sửa được

Ngày 2026-11-23, nhánh `dev`. **Chưa commit.** Tài liệu này ghi lại đợt điều tra "tại sao relay drain outbox lúc nhanh lúc chậm", kết luận, bản sửa và bằng chứng. Liên quan: [outbox-claim-optimization.md](outbox-claim-optimization.md) (đợt tối ưu claim trước, migration V49) và [feed-engagement-counts.md](feed-engagement-counts.md) (pipeline đếm like/comment mà test đầu-cuối chạy qua).

## 1. Tóm tắt một đoạn

Khi chạy test đầu-cuối `EngagementPipelineIT` (like/comment -> outbox -> relay -> Kafka -> listener -> `social.video_engagement`), cùng một lượng việc có lúc được relay xử lý xong trong khoảng 3 giây, có lúc mất 13 đến 58 giây (có đợt 68 giây). Nguyên nhân **không phải** I/O, khóa hay vacuum, mà là **thống kê của bảng `platform.outbox_event` bị lạc hậu**: planner tin rằng gần như không có dòng nào đang chờ, nên chọn kế hoạch Nested Loop cho phần `selected` của câu claim, và kế hoạch đó đọc lại cả tập dòng claimable cho từng dòng. Tôi viết lại phần `selected` bằng `CROSS JOIN LATERAL` tra theo khóa chính, nên kế hoạch không còn phụ thuộc vào thống kê. Trước khi sửa 8/60 đợt đo bị nghẽn; sau khi sửa 0/60.

## 2. Triệu chứng

- Pipeline test: một đợt 4.000 ghi (3.000 like + 1.000 comment) tạo khoảng 8.000 dòng outbox (mỗi like/comment sinh 3 dòng outbox). Đa số đợt, relay xả hết trong khoảng 3 giây.
- Thỉnh thoảng cùng khối lượng đó mất 13 đến 58 giây, một đợt tới 68 giây. Điểm đáng chú ý là kết quả vẫn đúng, chỉ **trễ**: vấn đề là độ trễ của relay, không phải mất hay đếm sai dữ liệu.
- Trong lúc nghẽn, JDBC ping (`SELECT 1`) vẫn dưới 3 ms, JVM không bị dừng, không có checkpoint. Chỉ các câu truy vấn trên `outbox_event` chậm.

## 3. Điều tra: giả thuyết sai và cách loại bỏ

Giả thuyết đầu tiên của tôi là "I/O hoặc autovacuum trong Docker làm cả DB chậm". Giả thuyết này **sai**, và bằng chứng loại nó:

| Quan sát | Nếu là I/O / vacuum thì | Thực tế |
| --- | --- | --- |
| Truy vấn bảng khác trong lúc nghẽn | Cũng chậm | Nhanh bình thường (`SELECT 1` < 3 ms) |
| Trạng thái relay | Chờ I/O hoặc chờ khóa | Đang chạy CPU, không chờ sự kiện nào |
| Chỉ có `outbox_event` chậm | Không giải thích được | Khớp với "một câu trên bảng đó dùng kế hoạch tệ" |

Sau đó tôi đo từng bước: `pg_stat_statements` (câu claim chiếm gần hết thời gian), `EXPLAIN (ANALYZE, BUFFERS)` ngay trong lúc nghẽn, và đối chiếu `pg_stats` của `outbox_event` trước và sau mỗi đợt nghẽn. Hai thí nghiệm gây nhiễu (xem mục 9) đã được nhận diện và thay bằng cách đo không làm thay đổi trạng thái relay.

## 4. Nguyên nhân gốc (đã kiểm chứng)

### 4.1 Thống kê lạc hậu

Outbox gần như **luôn rỗng**: các dòng được gửi xong chuyển sang `PUBLISHED` rất nhanh. Khi autovacuum/autoanalyze chạy vào lúc yên tĩnh, thống kê cột `status` ghi nhận:

```
most_common_vals = {PUBLISHED}, most_common_freqs = {1}
```

nghĩa là planner tin rằng 100% dòng là `PUBLISHED`, tức **gần như không có dòng PENDING/RETRY nào**.

Rồi một đợt tăng đột biến đẩy hàng nghìn dòng `PENDING` vào. Autoanalyze chỉ chạy lại khi số dòng thay đổi vượt ngưỡng **và** worker rảnh, nên có một khoảng thời gian 13 đến 58 giây mà thống kê vẫn nói "không có gì đang chờ", trong khi thực tế có hàng nghìn dòng.

### 4.2 Kế hoạch Nested Loop sai

Câu claim có phần `selected` (bản cũ):

```sql
selected AS (
    SELECT o.event_id
    FROM platform.outbox_event o
    JOIN top USING (event_id)
    WHERE (o.status IN ('PENDING','RETRY') OR (o.status = 'CLAIMED' AND o.claimed_until < now()))
      AND o.available_at <= now()
    FOR UPDATE OF o SKIP LOCKED
)
```

Với thống kê ở 4.1, planner ước lượng bộ lọc trên `o` chỉ còn khoảng 1 dòng. Với 1 dòng "ngoài", Nested Loop (đọc `o`, rồi với mỗi dòng đó đi dò trong `top`) trông rẻ nhất. Nhưng thực tế có khoảng 7.500 dòng claimable, và `top` là một CTE có `ORDER BY ... LIMIT` không thể tra theo khóa nên bị **chạy lại cho từng dòng** của `o`:

- Kế hoạch chụp được trong lúc nghẽn: `Join Filter` loại **749.700 dòng**, riêng bước này mất khoảng **1.008 ms** cho **mỗi lần claim**.
- Một mình trong Postgres riêng với đúng tình huống thống kê đó: Join Filter loại 799.900 dòng, **139 ms**; sau `ANALYZE` chỉ còn **1,1 ms** (cùng câu, cùng dữ liệu, chỉ khác thống kê).
- Relay claim lặp lại (vòng drain tối đa 20 batch/lần chạy), nên mỗi claim chậm cộng dồn thành hàng chục giây.

### 4.3 Vì sao "13 đến 58 giây" chứ không phải cố định

Thời gian nghẽn = thời gian tới lần autoanalyze kế tiếp của `outbox_event`. Autoanalyze phụ thuộc ngưỡng thay đổi và lịch của autovacuum launcher nên không cố định.

Bằng chứng nhân quả:
- Đợt nghẽn 68 giây: thống kê trước đợt đo là `{PUBLISHED}, freq 1`.
- Đợt nghẽn 30 giây: autoanalyze chạy **giữa chừng**, thống kê chuyển thành `PENDING` khoảng 1,5%, và claim hồi phục ngay sau đó. Đợt kế tiếp lại nhanh.

### 4.4 Đường phụ: kế hoạch đã lưu từ lúc bảng rỗng

Có một đường dẫn thứ hai tới cùng triệu chứng. Relay poll mỗi 500 ms kể cả khi outbox rỗng, nên prepared statement của driver chạy rất nhiều lần trên bảng rỗng. Sau vài lần, server chuyển sang **generic plan** (kế hoạch dùng lại), và kế hoạch đó được lập cho bảng rỗng: sequential scan dưới Nested Loop, rẻ trên bảng rỗng nhưng mất **3,2 giây** khi có 8.000 dòng chờ (**1,5 ms** nếu lập kế hoạch cho đúng bảng hiện tại). Kế hoạch này nằm trên connection được pool giữ lại, nên kéo dài đến khi connection bị thay.

## 5. Bản sửa

Hai thay đổi trong [OutboxRepository.java](../backend/shared/outbox/src/main/java/com/shortvideo/shared/outbox/OutboxRepository.java).

### 5.1 Chính: tra theo khóa chính bằng LATERAL

Phần `selected` mới:

```sql
selected AS (
    SELECT o.event_id
    FROM top
    CROSS JOIN LATERAL (
        SELECT o.event_id
        FROM platform.outbox_event o
        WHERE o.event_id = top.event_id
          AND (o.status IN ('PENDING','RETRY')
               OR (o.status = 'CLAIMED' AND o.claimed_until < now()))
          AND o.available_at <= now()
        FOR UPDATE OF o SKIP LOCKED
    ) o
)
```

Phần `candidates`, `top` và `UPDATE ... RETURNING` giữ nguyên.

### 5.2 Phụ: `SET LOCAL plan_cache_mode = force_custom_plan`

`claimBatch` chạy trong transaction riêng (`REQUIRES_NEW`), và câu đầu tiên của nó là `SET LOCAL plan_cache_mode = force_custom_plan`. `LOCAL` hết hiệu lực khi transaction kết thúc nên không ảnh hưởng gì khác trên connection. Chi phí: khoảng 0,2 ms để lập kế hoạch mỗi lần poll.

## 6. Vì sao bản sửa này hoạt động

**LATERAL khóa chặt kế hoạch.** Với `CROSS JOIN LATERAL`, vế phải được tính **một lần cho mỗi dòng của `top`**, và bên trong nó điều kiện `o.event_id = top.event_id` là điều kiện bằng trên khóa chính. Planner chỉ còn một cách thực tế để thực hiện: *Index Scan theo khóa chính* cho từng dòng của `top`. Không có lựa chọn "đọc cả `o` rồi join" nào cho nó cân nhắc theo thống kê, vì LATERAL buộc thứ tự "lấy từng `top`, rồi tra `o`".

Hệ quả:
- Chi phí = (số dòng của `top`, tối đa `batchSize` = 100) x (một lần tra khóa chính), **không phụ thuộc** vào số dòng pending hay vào việc thống kê đúng hay sai.
- Trước: chi phí phụ thuộc vào ước lượng số dòng -> thống kê sai thì kế hoạch sai.
- Sau: kế hoạch gần như bất biến theo thống kê.

**Ngữ nghĩa giữ nguyên.** `FOR UPDATE OF o SKIP LOCKED` vẫn khóa chỉ các dòng này, vẫn bỏ qua dòng đang bị relay khác khóa. Điều kiện trạng thái được kiểm lại bên trong LATERAL, vẫn chặn trường hợp dòng bị relay khác claim giữa bước chọn `top` và bước khóa (đây là điều bảo vệ khỏi claim hai lần). Test hai relay tranh nhau vẫn xanh.

**`force_custom_plan` đóng đường phụ.** Mọi lần claim lập kế hoạch cho trạng thái bảng lúc đó, nên một generic plan lập từ lúc bảng rỗng không thể sống sót. Hai thay đổi bổ sung nhau: LATERAL làm kế hoạch không nhạy với thống kê; custom plan làm kế hoạch không nhạy với lịch sử connection. Tách riêng, mỗi thay đổi đã đủ chặn một đường, nhưng giữ cả hai thì không còn đường nào đã biết.

## 7. Bằng chứng sau khi sửa

| Thước đo | Trước | Sau |
| --- | --- | --- |
| Claim trong Postgres riêng, thống kê lạc hậu | 139 ms | 1,4 ms |
| Test hồi quy `aClaimStaysFastWhenTheStatisticsSayNothingIsWaiting` (claim chậm nhất trong 3 lần) | đỏ: 637 ms (ngưỡng 100 ms) | xanh |
| Bài săn nghẽn 60 đợt burst: số đợt nghẽn | 8/60 (tới 58 giây) | **0/60** |
| Thời gian đuổi kịp sau khi ghi xong | tới 58 giây | 2,4 đến 4,5 giây (trung vị 2,7 giây) |
| Mỗi lần claim trong pipeline test | 0,3 đến 1 s khi thống kê lạc hậu | trung bình 1,5 đến 7 ms mỗi đợt |

Điều kiện gây lỗi có thật trong bài đo sau khi sửa: **42/60 đợt** bắt đầu khi thống kê đang là `{PUBLISHED}` 100%, tức bài thử đã chạm đúng tình huống từng gây nghẽn nhiều lần mà không nghẽn.

## 8. Test đi kèm

- [OutboxClaimIT](../backend/app/src/test/java/com/shortvideo/app/OutboxClaimIT.java), thêm 2 test (cả file 7 test, xanh):
  - `aClaimIsPlannedForTheTableItFindsNotFromAPlanCachedWhileTheOutboxWasEmpty`: chạy `claimBatch` rồi kiểm tra `plan_cache_mode` của transaction là `force_custom_plan`.
  - `aClaimStaysFastWhenTheStatisticsSayNothingIsWaiting`: chèn 100.000 dòng `PUBLISHED`, `ANALYZE`, chèn thêm 20.000 dòng `PENDING`, rồi đo 3 lần `claimBatch(..., 100, ...)`; mỗi lần trả đúng 100 dòng và chậm nhất < 100 ms. Test này **đỏ trên câu cũ** (637 ms), nên là test hồi quy thật chứ không phải test luôn xanh.
- [EngagementPipelineIT](../backend/app/src/test/java/com/shortvideo/app/EngagementPipelineIT.java) (file mới): chạy thật Postgres + Kafka (topic 3 partition như docker-compose), kiểm tra sau burst: tổng like/comment lưu đúng bằng số đã ghi, không có dòng `DEAD`, và một vòng reconcile không sửa dòng nào (không mất, không đếm đôi). In thêm độ trễ trên dòng `[PIPELINE]`. Một like đơn lẻ hiện sau khoảng 220 ms.
- Chạy xanh: test đơn vị module outbox, `OutboxClaimIT`, `OutboxBacklogMetricIT`, `OutboxDeadMetricIT`; `test-compile` toàn bộ OK.

## 9. Sai sót và nhiễu trong lúc điều tra (để lần sau không lặp lại)

- **Chẩn đoán đầu sai.** "Cả DB nghẽn do I/O/autovacuum" không có bằng chứng, chỉ là phỏng đoán; đã nói rõ và kiểm chứng lại (mục 3).
- **`DEALLOCATE ALL` trên connection pool** để thăm dò làm xáo trộn chính relay và che mất chế độ chậm; thay bằng connection riêng ngoài pool.
- **`postgres.getLogs()`** của Testcontainers chỉ trả log khởi động nên không lấy được `auto_explain`; chuyển sang `EXPLAIN ANALYZE` ngay trong test.
- **`pg_stat_statements` ở PG 17.4** không có cột `generic_plan_calls`; chuyển sang đối chứng A/B bằng `prepareThreshold=0`.
- **Số "relay done = -1 ms"** vô nghĩa vì outbox không bao giờ về 0 (listener sinh thêm dòng `notification.created`); không dùng số đó.

## 10. Còn lại / chưa làm

- **Một lần claim đơn lẻ mất 105 ms** trong 60 đợt đo sau khi sửa (trung bình các đợt vẫn 1,5 đến 7 ms). Chưa điều tra nguyên nhân; chưa có dấu hiệu liên quan tới thống kê.
- **Tốc độ ghi bền vững vượt khả năng relay** vẫn làm backlog tăng; chưa đo ngưỡng này. Mọi loại sự kiện chung một hàng đợi FIFO, nên backlog của loại này làm trễ loại khác.
- **Cảnh báo giao dịch mở lâu:** nguyên nhân duy nhất tìm thấy cho bloat (xmin horizon bị giữ). Nên có cảnh báo cho transaction mở quá lâu.
- **Cân nhắc tinh chỉnh autovacuum riêng cho `platform.outbox_event`** (`autovacuum_analyze_scale_factor` nhỏ) để thống kê tươi hơn. Không còn cần thiết cho tính đúng của claim sau bản sửa, nhưng giúp các câu truy vấn khác trên bảng này (ví dụ metric backlog) nếu sau này chúng nhạy với thống kê.
- Cả hai sửa đổi và hai file test **chưa commit**. `git status` còn các thay đổi không thuộc đợt này (module feed, `docs/feed-capacity-report.md`, `tests/load/seed-feed.sh`...); khi commit nên chọn đúng file.

## 11. Danh sách file thay đổi

| File | Thay đổi |
| --- | --- |
| `backend/shared/outbox/src/main/java/com/shortvideo/shared/outbox/OutboxRepository.java` | LATERAL trong `selected`, `SET LOCAL plan_cache_mode`, javadoc giải thích |
| `backend/app/src/test/java/com/shortvideo/app/OutboxClaimIT.java` | +2 test hồi quy |
| `backend/app/src/test/java/com/shortvideo/app/EngagementPipelineIT.java` | **mới**, test đầu-cuối pipeline |
