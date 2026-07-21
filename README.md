# pdhbar
a MIT based tample for sunmi PDAs

## Licensing

The pdhbar source code is licensed under the root [MIT License](LICENSE).

The bundled UIKitInsight AAR is a separately licensed component from
[aesxu2345/insight-board-kit](https://github.com/aesxu2345/insight-board-kit).
It is not covered by pdhbar's MIT License. See:

- [UIKitInsight license notice](uikit/LICENSE)
- [UIKitInsight commercial integration license](uikit/COMMERCIAL-INTEGRATION-LICENSE.md)
- [UIKitInsight NOTICE](uikit/NOTICE)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## POST contract for breakfast inquiry

The client currently calls POST /query_breakfast_right with a JSON body shaped like this:

```json
{
  "order_code": "T202607010001",
  "card_no": null
}
```

### Request notes
- `order_code` is required and is populated from the scanned barcode or card number.
- `card_no` is optional and may be omitted when no card number is available.
- The runtime uses an HTTP/1.1 raw socket request with `Content-Type: application/json; charset=utf-8`.

### Success conditions
The request is treated as successful when:
- the HTTP status code is in the `200-299` range, and
- the response does not contain a business `code` field, or `code` is `200`.

### Response fields
The response is expected to contain a payload similar to:

```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "order_code": "T202607010001",
    "card_no": "123456",
    "name_b64": "5byg5LiJ",
    "sex_b64": "55S3",
    "phone": "138****8888",
    "avatar_b64": "<base64 of a 358x441 JPEG>",
    "avatar_url": "",
    "birth_date": "1990-01-01",
    "package_name_b64": "5L2T5qOA5aWX6aSQ",
    "has_breakfast": true,
    "allow_breakfast": true,
    "breakfast_status": 0,
    "reason_b64": ""
  }
}
```

### Photo contract
- `avatar_b64` is the preferred photo field for the approval dialog.
- The value must be a pure Base64 JPEG payload without a `data:image/...` prefix.
- The decoded image must be strictly `358 × 441` pixels.
- If `avatar_b64` is invalid, empty, or not the required size, the dialog shows a placeholder instead of blocking the UI.
- `avatar_url` remains a reserved field for future expansion; the current client does not load images from it.
- When both fields are present, `avatar_b64` takes precedence.

### UI behavior
- The approval dialog shows a fixed-size photo placeholder immediately while the photo is being decoded.
- A new scan cancels the previous photo-loading work so stale photo results do not appear for the next customer.
- The client uses `allow_breakfast` and `breakfast_status` as the business gating inputs; `has_breakfast` is treated as informational only.
