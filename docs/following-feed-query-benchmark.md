# Vì sao query của tab Following có lúc nhanh, có lúc chậm

Tài liệu này giải thích kết quả đo của query `FIND_ELIGIBLE_BY_CREATORS` trong
`EligibilityRepository`: lấy tối đa 200 video eligible, mới nhất trước, của các
creator mà viewer đang follow.

> **Lưu ý về độ tin cậy.** Số liệu dưới đây đo trên dữ liệu **giả lập** (1,5 triệu
> video, 20.000 creator, viewer follow 500 creator), Postgres 16 chạy trong Docker
> trên máy local, mỗi trường hợp đo **một lần**. Chúng đủ để thấy xu hướng
> (nhanh gấp trăm lần hay không), chưa đủ để dùng làm con số tuyệt đối.

---

## 1. Câu query

```sql
SELECT ...
FROM eligibility.video_eligibility v
JOIN eligibility.account_eligibility a ON a.account_id = v.creator_id
WHERE v.creator_id = ANY(?)          -- 500 creator đang follow
  AND v.is_video_eligible = true
  AND a.is_account_eligible = true
ORDER BY v.updated_at DESC           -- mới nhất trước
LIMIT 200
```

Câu này chỉ nói **muốn gì**. Postgres (cụ thể là *query planner*) tự chọn **cách
làm**. Chọn cách nào quyết định nhanh hay chậm, và đó là gốc của mọi kết quả bên
dưới.

## 2. Hai cách làm mà planner có thể chọn

Hình dung bài toán là: *"Tìm 200 cuốn sách mới nhất của 500 tác giả, trong một thư
viện 1,5 triệu cuốn."*

### Cách A: đi theo kệ "sách mới nhất" (index toàn cục)

Có sẵn một kệ xếp **toàn bộ sách theo ngày, mới nhất trước**
(`video_eligibility_eligible_updated_idx`). Cách làm: đứng ở đầu kệ, lần lượt cầm
từng cuốn lên, **xem tác giả có nằm trong 500 người kia không**; nếu có thì lấy,
nếu không thì bỏ qua. Dừng khi đủ 200.

- Nếu 500 tác giả này **đang viết đều đặn**, cứ vài cuốn trên kệ lại có một cuốn
  của họ, nên chỉ cần xem vài trăm cuốn là đủ 200. **Rất nhanh.**
- Nếu 500 tác giả này **đã ngừng viết từ lâu**, sách của họ nằm tận cuối kệ. Bạn
  phải cầm lên và bỏ qua **hàng triệu cuốn của người khác** mới gặp được sách của
  họ. **Rất chậm.**

### Cách B: đến thẳng từng tác giả (index theo creator)

Có sẵn một mục lục theo tác giả (`video_eligibility_creator_idx`). Cách làm: với
mỗi trong 500 tác giả, **lấy hết sách của họ**, gom lại thành một chồng, **sắp theo
ngày**, rồi lấy 200 cuốn trên cùng.

- Số việc phải làm **chỉ phụ thuộc vào tổng số sách của 500 người này**, không
  phụ thuộc họ viết gần đây hay từ lâu.
- Không bao giờ nhanh đến mức "vài cuốn là xong", nhưng cũng **không bao giờ
  thảm họa**.

## 3. Vì sao planner chọn sai

Planner không biết trước dữ liệu thật. Nó dựa vào thống kê chung và **giả định
rằng sách của 500 tác giả này rải đều theo thời gian** trên kệ. Với giả định đó,
Cách A trông rất rẻ ("chỉ cần xem vài trăm cuốn"), nên nó chọn Cách A.

Giả định này sai đúng vào tình huống mà lỗi cũ của tab Following sinh ra: creator
được follow **ít đăng**, còn người khác **đăng rất nhiều video mới**. Planner vẫn
tin vào giả định đã có, nên đi vào Cách A và phải bỏ qua hàng triệu hàng.

## 4. Kết quả đo

Thời gian thực thi (ms), 500 creator được follow, `LIMIT 200`:

| Tình huống | ① Query hiện tại | ② Thêm index `(creator_id, updated_at DESC)` | ③ `ORDER BY updated_at + interval '0'` | ④ `LATERAL` |
|---|---:|---:|---:|---:|
| 500 creator **ngẫu nhiên** | 23 | chưa đo | 57 | 58 |
| Creator **ngủ đông** (video đều cũ; đúng tình huống lỗi cũ) | **1.300** | **1.256** | **12** | 21 |
| 500 creator **đăng nhiều nhất** | 1,4 | 1,6 | 85 | 437 |

### ① Query hiện tại: nhanh khi may mắn, chậm khi gặp creator ngủ đông

Planner chọn **Cách A**.

- Creator hoạt động bình thường: 23 ms hoặc 1,4 ms. Chỉ cần xem vài trăm hàng.
- **Creator ngủ đông: 1.300 ms.** Postgres phải duyệt và loại **1.314.660 hàng**
  (log ghi `Rows Removed by Filter: 1314660`) rồi mới đủ 200.

Đây là điểm đáng lo nhất: thời gian không ổn định, và **tệ nhất ở đúng trường hợp
mà tính năng này sinh ra để phục vụ**.

### ② Thêm index `(creator_id, updated_at DESC) WHERE is_video_eligible`: không đổi gì

Đây là đề xuất đầu tiên của tôi, nhưng đo thì **1.256 ms so với 1.259 ms**, không
khác. Lý do: index mới chỉ **thêm lựa chọn** cho planner. Planner vẫn tin giả định
"rải đều" ở mục 3 nên vẫn chọn Cách A, và index mới không được dùng.

Bài học: thêm index chưa chắc cải thiện nếu planner không chọn nó. Cần đo bằng
`EXPLAIN (ANALYZE, BUFFERS)` trước khi viết migration.

### ③ `ORDER BY v.updated_at + interval '0' DESC`: chậm hơn một chút nhưng ổn định

Cộng thêm `interval '0'` không đổi kết quả, nhưng khiến Postgres **không còn coi
cột `updated_at` là đã sắp xếp sẵn theo index toàn cục**. Nó mất lựa chọn Cách A và
buộc dùng **Cách B**.

- Ngủ đông: 1.300 ms xuống **12 ms** (nhanh hơn khoảng 100 lần).
- Ngẫu nhiên và nhiều video: 57 ms và 85 ms. Chậm hơn query hiện tại ở hai trường
  hợp này (23 ms và 1,4 ms), vì **không còn đường tắt**; nó phải lấy hết video của
  500 creator rồi sắp xếp.

Đánh đổi: **mất đi những lần siêu nhanh, đổi lấy việc không bao giờ bị chậm cả
giây**. Chi phí tỷ lệ với số video của các creator được follow, mọi kết quả đo
được đều dưới 100 ms.

Không cần migration, vì Cách B dùng index `video_eligibility_creator_idx` có sẵn từ
`V6__eligibility.sql`.

### ④ `LATERAL`: ổn định nhưng làm thừa việc

`LATERAL` yêu cầu lấy **tới 200 video của từng creator** rồi mới gộp và chọn 200
tốt nhất. Để chắc chắn đúng, mỗi creator phải được phép góp tối đa 200 (vì cả 200
video đầu có thể đến từ cùng một người).

- Creator ít video: nhanh (21 ms ở ngủ đông, 58 ms ở ngẫu nhiên).
- Creator nhiều video: 500 creator × 200 hàng = khoảng 100.000 hàng bị kéo lên rồi
  bỏ gần hết, nên **437 ms**.

Nó ổn định nhưng không tốt hơn ③ ở bất kỳ trường hợp nào đã đo, nên không đáng
chọn.

## 5. Tóm tắt một dòng cho mỗi phương án

| Phương án | Tính chất | Nhận xét |
|---|---|---|
| ① Hiện tại | **Nhanh hoặc rất chậm, tùy dữ liệu** | Chậm nhất đúng ở tình huống cần nhất |
| ② Thêm index | Không đổi | Planner không dùng nó, không đáng tạo |
| ③ `+ interval '0'` | **Luôn tương đối nhanh, dự đoán được** | Đề xuất; thêm comment giải thích trong code |
| ④ `LATERAL` | Ổn định nhưng làm thừa | Không tốt hơn ③ |

## 6. Điều chưa chắc chắn

- Dữ liệu giả lập, phân bố thật (creator nổi tiếng đăng nhiều, người ít đăng)
  có thể khác.
- Mỗi tình huống đo một lần; cache của Postgres có thể làm lệch vài lần chạy đầu.
- Ranking của người đã đăng nhập được cache 30 phút, của khách 30 giây (xem
  `FeedService.feed()`), nên query này chỉ chạy khi mở feed (page 0) hoặc cache hết
  hạn. Tức là **không phải đường nóng nhất**, nhưng vẫn đáng tránh cái đuôi hơn một
  giây.
- Thủ thuật `+ interval '0'` dựa vào cách planner hoạt động hiện tại. Nếu nâng cấp
  phiên bản Postgres, nên đo lại.

## 7. Cách tự đo lại

Chạy một Postgres tạm, tạo hai bảng `video_eligibility` và `account_eligibility`
cùng các index trong `V6__eligibility.sql` và `V12__eligibility_indexes.sql`, nạp
1,5 triệu video với phân bố lệch, rồi chạy:

```sql
EXPLAIN (ANALYZE, BUFFERS) <câu query>;
```

Chú ý hai dòng trong kết quả:
- Tên node: `Index Scan using video_eligibility_eligible_updated_idx` là Cách A,
  `Bitmap Heap Scan` kèm `video_eligibility_creator_idx` là Cách B.
- `Rows Removed by Filter`: số hàng bị duyệt rồi bỏ đi. Số này lớn cho thấy planner
  đang dùng Cách A trong tình huống không hợp.
