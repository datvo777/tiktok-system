# Vai trò nhân sự: MODERATOR và ADMIN

Hai vai trò nhân sự, tách theo nguyên tắc quyền tối thiểu. Ma trận dưới đây là hợp đồng; nó được kiểm bằng
`AuthorizationIT` (các test `aModerator…` và `anAdmin…`).

- **MODERATOR** làm việc với hàng đợi duyệt và hàng đợi báo cáo: quyết định theo từng video, có thể đảo ngược.
- **ADMIN** làm được mọi việc của MODERATOR, và giữ riêng những việc ảnh hưởng cả một tài khoản, không đảo
  ngược được, xét lại quyết định của moderator, hoặc là giám sát và vận hành.

ADMIN không "kế thừa" qua `RoleHierarchy`: mỗi route ghi rõ cả hai vai trò (`Roles.CAN_MODERATE`), để đọc
được điều route chấp nhận ngay tại chỗ khai báo.

| Nhóm | Route | MODERATOR | ADMIN |
|---|---|:-:|:-:|
| Hàng đợi duyệt | `GET /internal/v1/videos/pending`, `POST …/{id}/approve`, `…/reject` | ✅ | ✅ |
| Xem trước video | `POST /internal/v1/videos/{id}/moderator-playback-session` | ✅ | ✅ |
| Trạng thái video | `GET /internal/v1/videos/{id}` | ✅ | ✅ |
| Báo cáo | `GET /internal/v1/reports/open`, `POST …/{id}/resolve` | ✅ | ✅ |
| Kháng cáo | `/internal/v1/appeals/**` | ❌ | ✅ |
| Cách ly video | `POST /internal/v1/videos/{id}/quarantine` | ✅ | ✅ |
| Vòng đời video | `restore`, `remove`, `reprocess` | ❌ | ✅ |
| Tài khoản | `/internal/v1/accounts/**` | ❌ | ✅ |
| Nhật ký audit | `/internal/v1/audit/**` | ❌ | ✅ |
| Vận hành | `/actuator/**` (trừ liveness/readiness) | ❌ | ✅ |

Lý do các chỗ không hiển nhiên:

- **Kháng cáo là ADMIN**: nó là cấp xét lại một quyết định từ chối; moderator không nên tự duyệt kháng cáo của
  quyết định mình hoặc đồng nghiệp đã đưa ra.
- **`quarantine` là MODERATOR**: báo cáo về một video đã đăng cần một hành động ngăn chặn, mà `reject` chỉ
  áp dụng cho video đang chờ duyệt. Cách ly giữ nguyên asset, có audit và đảo ngược được.
- **`restore` là ADMIN**: đưa lại nội dung từng bị cách ly là chiều rủi ro hơn, và moderator không tự gỡ hold
  của đồng nghiệp.
- **`remove` là ADMIN**: đặt lịch xoá, không đảo ngược.
- **Audit là ADMIN**: nó ghi cả hành động của moderator.

## Mặc định đóng

`SecurityConfig` chỉ liệt kê các route moderator được vào. Mọi route khác dưới `/internal/**`, kể cả route thêm
sau này, chỉ dành cho ADMIN cho tới khi được liệt kê có chủ ý. Mỗi controller vẫn có `@PreAuthorize` riêng
(`Roles.CAN_MODERATE` hoặc `Roles.IS_ADMIN`) làm lớp thứ hai.

## Cùng mức bảo vệ với ADMIN

- **MFA**: `shortvideo.mfa.required-roles` mặc định `ADMIN,MODERATOR`.
- **Phiên**: `shortvideo.jwt.privileged-roles` mặc định `ADMIN,MODERATOR`, nên phiên bị giới hạn tuyệt đối
  12 giờ thay vì 30 ngày.
- **Xem trước video** (`MediaAuthorizer`) kiểm lại vai trò nhân sự ở bước phát, không chỉ tin vào claim trong
  token.

## Cấp vai trò

Vai trò nằm trong cột `account.account.roles` (chuỗi phân tách bằng dấu phẩy, không phân biệt hoa thường) và chưa
có API cấp. Cấp bằng SQL, ví dụ:

```sql
UPDATE account.account SET roles = 'USER,MODERATOR' WHERE email = '…';
```

Quản trị viên hiện có giữ `ADMIN` và vẫn làm được mọi việc của moderator; không cần di chuyển dữ liệu.
