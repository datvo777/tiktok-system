# Báo cáo: sức chứa Redis và Postgres của feed

Ngày 2026-11-14, nhánh `dev`. Báo cáo này tổng hợp việc review plan "đo sức chứa Redis và Postgres cho feed", những gì đã sửa trong code, và những gì đã (và chưa) đo. **Chưa có kết quả đo chính thức**: ma trận đầy đủ chưa chạy. Mọi con số ghi là "ước tính" là tính từ code, "smoke" là một lần chạy ngắn để kiểm tra kịch bản, không dùng để kết luận sức chứa.

## 1. Kết luận ngắn

1. Plan sai ở một giả định nền: TTL ranking của người đã đăng nhập là **30 phút**, không phải 30 giây (commit `105117d`, `ranking-session-ttl: 30m`). Bộ nhớ Redis ≈ số ranking mới mỗi giây × 1800 s × kích thước một ranking. Mỗi viewer mở feed rồi rời đi vẫn giữ một key tới hết TTL. Vì vậy **bộ nhớ Redis nhiều khả năng là nút thắt đầu tiên**, trái với nhận định C4 của plan (Postgres trước). Đây vẫn là suy ra từ code, chưa đo.
2. Rủi ro bảo mật có thật (C6): `session:revoked:*` chỉ nằm trong Redis, không có bản sao ở Postgres, và Redis cũ chạy `allkeys-lru`. Dưới áp lực bộ nhớ từ ranking, entry này bị loại và token đã đăng xuất dùng lại được. **Đã tái hiện bằng `check-eviction.sh`**, đã giảm bằng cách tách Redis, rồi đóng hẳn bằng bản sao bền ở Postgres (mục 3).
3. Đã làm ba thay đổi theo best practice: bỏ hai query đếm không dùng (O8), cache ranking chỉ lưu ID (O1), tách Redis cache khỏi Redis trạng thái (O6). Đã viết bộ đo; chưa chạy ma trận.

## 2. Đối chiếu các nhận định của plan

| # | Nhận định | Trạng thái | Căn cứ |
| --- | --- | --- | --- |
| C1 | Key ranking ~60–80 KB | **Gần đúng cho dạng cũ (ước tính ~65 KB, chưa đo); dạng mới đo được ~20 KB** | `MEMORY USAGE` trên profile A sau O1: 20.576 byte. Profile B, C chưa đo |
| C2 | Các key khác < 5% bộ nhớ | Chưa đo | Cần ma trận |
| C3 | Số key ≈ rate × TTL | **Sửa lại**: TTL là 30 phút cho người đăng nhập, 30 giây chỉ cho khách | `FeedProperties`, `application.yml` |
| C4 | Postgres bão hòa trước Redis | **Nhiều khả năng sai** (xem mục 1) | Suy ra từ TTL 30 phút; cần đo |
| C5 | Hit rate người đăng nhập thấp | **Đúng theo thiết kế, không phải lỗi**: page 0 luôn tính lại ranking, các trang sau đều là hit | `FeedService.feed` |
| C6 | Eviction làm token đăng xuất dùng lại được | **Đã tái hiện** | `check-eviction.sh`, mục 5 |
| C7 | Parse JSON 200 phần tử tốn CPU | Chưa đo. Sau O1 JSON nhỏ hơn nhiều nên ít quan trọng hơn | |
| C8 | 7/9 query không phụ thuộc viewer; share/view không dùng | **Đúng cho phần share/view** (xác nhận bằng grep: `countsForAll` chỉ `FeedService` gọi, `FeedScorer` chỉ đọc like và comment). Phần "7 query không theo viewer" chưa làm (O9) | |

Điều chỉnh khác so với plan:

- O2 (cắt description) **không an toàn**: [Feed.tsx:826](../web/src/Feed.tsx) hiển thị nguyên description.
- O5 (chống stampede) **đã có** cho ranking của khách (commit `58c229f`, single-flight).
- Query đếm view quét `video_view` trên cả pool 200 video, nặng hơn plan ngụ ý.
- Lỗi gõ trong plan: `sed_memory` là `used_memory`, `pdatedAt` là `updatedAt`.

## 3. Thay đổi trong code

### O8: bỏ hai query đếm không dùng
- Thêm `EngagementCounts` và `SocialDirectory.engagementForAll` (chỉ like và comment), thay `countsForAll` (đã xóa, cùng hai câu SQL share/view).
- Mỗi lần tính ranking: **9 → 7 query** (người đăng nhập), **7 → 5** (khách). Điểm số không đổi.
- Test: `EngagementCountsIT` (PostgreSQL thật; comment đã xóa không tính, video không hoạt động ra 0).

### O1: cache ranking chỉ lưu ID
- Key `feed:ranking:*` chỉ còn `{videoId, creatorId}` (`FeedRef`). Ước tính nhỏ hơn khoảng 3 lần cho profile A (65 KB → 20 KB đo được) và hơn 50 lần cho profile C (cột đầy).
- Khi phục vụ trang từ cache, hydrate 20 video bằng một query theo khóa chính (`findVideoEligibilities`). Video không còn eligible bị bỏ khỏi trang đó mà **không làm lệch offset** của các trang sau.
- Lần tính ranking mới vẫn dùng dữ liệu đầy đủ trong bộ nhớ, không thêm query.
- Đánh đổi: mỗi lần đọc cache (kể cả khách) thêm một query nhẹ. Bù lại title sửa hoặc video gỡ hiện ngay ở trang kế, không phải chờ key hết hạn.
- Test: hai test mới trong `FeedServiceSessionRankingTest` (metadata mới ở trang kế; video mất điều kiện bị bỏ mà offset giữ nguyên).

### Deny-list đăng xuất có bản sao bền ở Postgres
- Bảng mới `platform.revoked_session` (V50). Logout và refresh ghi Postgres trước, rồi mới ghi Redis; refresh single-use dùng chính câu `INSERT ... ON CONFLICT DO NOTHING` làm quyết định nguyên tử, nên không còn phụ thuộc Redis.
- Đọc: Redis có key thì bị thu hồi; Redis không có key chỉ coi là "chưa thu hồi" khi marker hoàn chỉnh còn đó (một `MGET` lấy cả hai). Marker mất (Redis restart hoặc flush) hoặc Redis lỗi thì hỏi Postgres. Chỉ khi cả hai lỗi mới cho qua.
- `SessionDenyListRebuilder` dựng lại Redis khi khởi động và mỗi phút, xóa hàng hết hạn, rồi mới ghi marker.
- Thay đổi hành vi có chủ ý: khi Redis chết, token đã đăng xuất **không còn** được chấp nhận lại (trước đây fail-open). `RedisOutageAuthIT` đã sửa theo.
- Còn một khe nhỏ: nếu Postgres nhận logout nhưng ghi Redis lỗi, rồi Redis lành lại trước lần rebuild kế (tối đa 1 phút), token vẫn qua trong khoảng đó.
- Test: `SessionDenyListDurabilityIT` (5 test: flush Redis rồi gọi lại token đã đăng xuất, rebuild, refresh không dùng lại được sau flush, hai refresh đồng thời chỉ một thắng, dọn hàng hết hạn).

### O6: tách Redis cache khỏi Redis trạng thái
- `docker-compose.yml`: thêm `redis-cache` (cổng 6380, `allkeys-lru`, 256 MB); `redis` chính đổi sang `noeviction`, 128 MB.
- App đọc `REDIS_CACHE_HOST` / `REDIS_CACHE_PORT`. Để trống thì feed cache dùng chung Redis chính như trước. `FeedCacheRedis` tự giữ kết nối riêng và không đăng ký bean `StringRedisTemplate` hay `RedisConnectionFactory`, vì bean đó sẽ tắt auto-config và trỏ nhầm mọi nơi khác sang Redis cache.
- Hệ quả cần biết: khi `redis` chính đầy, ghi sẽ lỗi (đăng xuất báo lỗi, không âm thầm bỏ qua). Đây là chủ ý.
- Test: `FeedCacheRedisIT` (hai Redis thật; ranking chỉ vào Redis cache).

## 4. Bộ đo (`tests/load/`)

| File | Việc |
| --- | --- |
| `seed-feed.sh A\|B\|C` | Nạp 400 video eligible + like/comment thẳng vào bảng projection và social |
| `feed-bench.mjs` | Mô phỏng người xem theo `Feed.tsx`; báo cáo client, server, Redis, và chiếu bộ nhớ ở TTL thật |
| `run-feed.sh` | Ma trận 500/2000/5000/10000 × profile A, C × 100%/30% đăng nhập × 3 lần; `QUICK=1` chạy một ô |
| `summarize-feed.mjs` | Bảng tổng hợp + pass/fail theo tiêu chí |
| `check-eviction.sh` | Kiểm C6 |
| `redis-sample.sh` | Lấy mẫu Redis thủ công |

Hai điểm thiết kế đáng nhớ:

- **Chiếu theo TTL thật.** Một lần chạy vài phút không bao giờ chạm plateau 30 phút, nên bench đo tốc độ tạo ranking và kích thước key rồi tính bộ nhớ ở TTL 1800 s, báo cạnh số đo thực.
- **Phiên bắt đầu ở vị trí ngẫu nhiên.** Nếu mọi viewer mở feed cùng lúc, các page 0 sau đó nằm ngoài cửa sổ đo và bench không thấy việc tính ranking.

Tiêu chí pass (đề xuất, chỉnh trước khi đo): lỗi < 1%; Hikari pending ≤ 5; p95 page 0 ≤ 2× mức tải thấp nhất cùng profile; không eviction; bộ nhớ (đo và chiếu) ≤ 70% `maxmemory`.

## 5. Những gì đã chạy

### Test tự động (đều qua)
- Unit: `FeedScorerTest`, `FeedRankingOrderTest`, `FeedServiceSessionRankingTest` (8 test), `SocialServiceTest`.
- Integration (Testcontainers): `EngagementCountsIT`, `FollowingCandidatesIT`, `FeedAuthenticationIT`, `FeedCacheRedisIT`, `RedisOutageAuthIT`, `ResilienceIT`.
- `AuthorizationIT` lỗi 31 test vì cần Redis ở `localhost:6379` mà máy không chạy lúc đó. Không liên quan thay đổi, chưa chạy lại với Redis bật.
- Chưa chạy toàn bộ suite.

### C6, kiểm bằng `check-eviction.sh`
Redis chính hạ xuống 32 MB `allkeys-lru`, đăng xuất một token, rồi tạo tải 3000 viewer.

| Bố cục | Số ranking trong Redis chính | Evicted | Token đã đăng xuất |
| --- | --- | --- | --- |
| Dùng chung một Redis (cấu hình cũ) | 1516 | 18.874 | **được chấp nhận lại (BUG)** |
| Cache ở Redis riêng | 0 | bộ đếm đứng nguyên ở 18.874, tức không có eviction mới | vẫn bị từ chối (OK) |

Lưu ý: `evicted_keys` là bộ đếm tích lũy của Redis và lúc chạy script chưa reset nó, nên lần hai in lại đúng số của lần một. Kết luận không đổi (không tăng thêm, token vẫn bị từ chối). Script đã được sửa để reset bộ đếm trước mỗi lần chạy, nhưng mình chưa chạy lại hai lần sau khi sửa.

### Smoke (không phải kết quả)
300 viewer, 70% đăng nhập, phiên 10 video × 5 s, cửa sổ 60 s, profile A, một Redis dùng chung:

- Lỗi 0%; feed khoảng 5,4 req/s; trung bình phía server 13,4 ms.
- p95 page 0: 24 ms (đăng nhập), 11 ms (khách). Hikari pending 0.
- Key ranking trung bình 20.574 byte.
- Chiếu bộ nhớ ở TTL 30 phút ra 105% `maxmemory`, nhưng đây là **hiện vật của phiên rất ngắn** (10 video, tức một viewer tạo ranking mới mỗi 50 giây) và đếm cả mọi lệnh `SET` trên Redis dùng chung. Không dùng con số này.

## 6. Hạn chế của bộ đo

- `pgTxPerSec` gồm việc nền (outbox, scheduler): đọc như hiệu so với mức tải thấp nhất.
- `setsPerSec` đếm mọi `SET`; chỉ dùng cho phần chiếu khi cache ở Redis riêng.
- Video seed đi thẳng vào projection, không qua upload và kiểm duyệt.
- Client và backend chung một máy; `clientCpuCores` được báo để thấy khi bộ tạo tải bão hòa.
- Mô hình hành vi ("mỗi viewer một video mỗi 10 s, phiên 30 video") là giả định, suy từ `Feed.tsx`, chưa quan sát thật.

## 7. Việc còn lại

1. **Chạy ma trận đầy đủ** (`tests/load/run-feed.sh`, khoảng 2–3 giờ, cần 10.000 tài khoản seed) với cache ở Redis riêng, rồi `summarize-feed.mjs`. Đây là bước biến các ước tính ở mục 1 thành số đo. Nên chạy thêm profile B và C để chốt C1.
2. **Triển khai**: đặt `REDIS_CACHE_HOST` ở môi trường thật và chạy lại `docker compose up` để có `redis-cache`. Nếu bỏ qua, mọi thứ vẫn chạy nhưng rủi ro eviction (C6) vẫn còn.
3. **Metric và cảnh báo**: giữ bộ đếm hit/miss thành metric chính thức; thêm cảnh báo `used_memory` > 80% `maxmemory` và `evicted_keys > 0` vào `infrastructure/prometheus/alerts.yml`. Hiện chưa có exporter Redis nào được scrape.
4. **O9** (tách phần dùng chung của ranking khỏi phần theo viewer) và **O7** (Caffeine cho ranking của khách): chỉ làm nếu số đo cho thấy Postgres là nút thắt, và O9 cần thiết kế cơ chế ổn định phân trang đi kèm.
5. **Mục tiêu tải của dự án** (bao nhiêu viewer đồng thời, bao nhiêu phần đã đăng nhập) vẫn chưa có; cổng "dừng nếu chưa chạm giới hạn" của plan cần nó.

## 8. Trạng thái repo

Chưa commit. Thay đổi: module `feed` và `social`, `application.yml`, `.env.example`, `docker-compose.yml`, hai integration test mới, các file `tests/load/` và hai tài liệu trong `docs/` ([feed-capacity-benchmark.md](feed-capacity-benchmark.md) cho cách chạy, file này cho kết quả review).
