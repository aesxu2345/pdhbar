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
- `avatar_b64` and `avatar_url` are both optional; neither field is required for a successful customer response.
- `avatar_b64` is temporarily bypassed: the current client neither parses nor displays it.
- The approval dialog currently loads photos only from `avatar_url`.
- Absolute HTTP(S) URLs are used directly. Relative URLs are resolved against the backend scheme, host, and port used for the customer query.
- A missing, invalid, unreachable, or non-image URL leaves the fixed placeholder visible.
- Missing or failed photo data does not prevent the remaining customer fields from being parsed and displayed independently.

### UI behavior
- The approval dialog and all customer fields appear immediately with a fixed-size photo placeholder; `avatar_url` loads independently and may finish later.
- A new scan replaces the customer model and cancels the previous URL image request so stale photo results do not appear for the next customer.
- The client uses `allow_breakfast` and `breakfast_status` as the business gating inputs; `has_breakfast` is treated as informational only.
