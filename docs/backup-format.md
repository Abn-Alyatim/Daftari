# Daftari backup format (`.daftari`) — schema 2.2

One file format is used everywhere: Google Drive backups (OAuth, hidden app folder), «حفظ نسخة في Drive» / «استيراد نسخة»
through the system picker, the daily local copy, sharing a backup over WhatsApp, onboarding «استرجاع نسخة», and giving the
file to «مدين» in the chat (it answers questions about it offline and can import it).

Source of truth: `android/app/src/main/java/com/daftari/app/backup/DaftariFile.kt`.

## File name

`Daftari-YYYY-MM-DD-HHmm.daftari` (the phone's local time), e.g. `Daftari-2026-10-29-0905.daftari`.

## Container

A standard ZIP archive with two entries:

| entry | when | content |
|---|---|---|
| `manifest.json` | always | plain JSON, never encrypted |
| `data.json` | unencrypted backup | UTF-8 JSON: the whole book |
| `data.json.enc` | encrypted backup | AES-256-GCM ciphertext of the same `data.json` bytes |

### manifest.json

```json
{
  "format": "daftari-backup",
  "schemaVersion": 2,
  "appVersion": "1.1.0",
  "createdAt": "2026-10-29T06:05:00Z",
  "createdAtMillis": 1793253900000,
  "file": "data.json",
  "sha256": "<hex SHA-256 of the plain data.json bytes>",
  "size": 48213,
  "counts": { "customers": 34, "entries": 379, "debts": 212, "payments": 167, "reminders": 3, "log": 431 },
  "logHead": { "seq": 431, "hash": "<sha256 of the newest log row>" },
  "encrypted": false
}
```

Encrypted backups add:

```json
"encryption": { "cipher": "AES-256-GCM", "kdf": "PBKDF2WithHmacSHA256", "iterations": 120000, "salt": "<base64, 16 bytes>", "iv": "<base64, 12 bytes>" }
```

Key = PBKDF2-HMAC-SHA256(password, salt, 120 000 iterations) → 256-bit AES key. The password («كلمة سر النسخ») is never written
into the file or sent anywhere; the phone keeps only the derived key + salt so automatic backups can be encrypted unattended.

### data.json

Money is written as a decimal number in major units (e.g. `13950` or `5000.5`); dates are ISO `YYYY-MM-DD`; timestamps are epoch ms.

```json
{
  "format": "daftari-backup", "schemaVersion": 2, "appVersion": "1.1.0",
  "createdAt": "2026-10-29T06:05:00Z", "createdAtMillis": 1793253900000,
  "bookId": "6f0c2b…", "currency": "YER", "business": "بقالة مدين", "owner": "مدين", "role": "SHOP_OWNER",
  "customers": [
    { "id": 1, "name": "محمد", "phone": "+967771234567", "kind": "CUSTOMER", "currency": "YER",
      "openingBalance": 0, "creditLimit": 0, "periodicAmount": 0, "period": "NONE", "startDate": "2026-09-19",
      "shopNumber": "", "activity": "", "category": "", "notes": "", "location": "", "unitsCount": 1,
      "createdAt": 1790000000000, "updatedAt": 1793253900000 }
  ],
  "entries": [
    { "id": 7, "customerId": 1, "type": "payment", "amount": 13950, "currency": "YER",
      "originalCurrency": "SAR", "originalAmount": 100, "rate": "139.5",
      "method": "transfer", "transferNo": "123456", "transferCompany": "الكريمي",
      "date": "2026-10-29", "items": "", "note": "", "collector": "", "receiptNo": "", "createdAt": 1793253900000 }
  ],
  "reminders": [ { "id": 1, "customerId": 1, "at": 1793340000000, "text": "طالب محمد", "done": false } ],
  "log": [
    { "seq": 1, "at": 1790000000000, "actor": "المالك", "device": "…", "via": "يدوي", "action": "CREATE", "entity": "PARTY",
      "entityId": 1, "partyName": "محمد", "title": "إضافة «محمد»", "before": "", "after": "{…snapshot…}", "refSeq": 0,
      "prevHash": "0000000000000000000000000000000000000000000000000000000000000000", "hash": "<sha256>" }
  ]
}
```

Field notes

* `type`: `debt` or `payment`. `amount` is always in the customer's account currency (`currency`).
* Multi-currency: when money was given or paid in another currency, `originalCurrency` + `originalAmount` + `rate`
  (1 unit of the original = `rate` account units, the rate used at that moment) are present and `amount` is the converted value.
  Changing the default currency or the rate table never rewrites stored entries.
* `method`: `cash` · `transfer` · `wallet` · `other` (absent = not recorded).
  * حوالة: `transferCompany` (الكريمي · النجم · الحزمي or a typed name), `transferNo` (optional).
  * محفظة (2.1, app 1.1.1): `transferCompany` = wallet (ون كاش · جيب · جوالي · فلوسك · حاسب or a typed name), `transferNo` = transaction number (optional), `senderPhone` (optional).
* `schemaMinor` (2.1+): additive changes only, so a 2.0 reader (app 1.1.0) still opens the file. On read, 2.0 rows are migrated:
  a number with no method becomes `transfer`, a wallet name typed as a transfer company becomes `wallet`, known spellings are made canonical.
* `rates` (2.2, app 1.1.2, optional): the exchange-rate table `{"city": "صنعاء", "cities": [{"city", "sarBuy", "sarSell", "usdBuy", "usdSell", "date"}]}`.
  Only the شراء (`*Buy`) rates are ever used in a calculation; بيع (`*Sell`) is kept for reference. On restore it is applied only when the phone has no table of its own.
* `log` is the whole «السجل»: append-only; each row's `hash` = SHA-256 over its fields and `prevHash`, `before`/`after` are full JSON snapshots.
* Old 1.0.x JSON backups (`{"app":"daftari", "parties":[…], "entries":[…]}`) are still accepted and migrated on read.

## Reading rules

1. ZIP magic `PK\x03\x04`; otherwise the reader tries the 1.0.x JSON format.
2. `manifest.format` must be `daftari-backup`; a `schemaVersion` newer than the app → «النسخة من إصدار أحدث… حدّث التطبيق».
3. Encrypted → ask for «كلمة سر النسخ»; a wrong password fails the GCM tag check («كلمة سر النسخ غلط»).
4. SHA-256 of the plain `data.json` must equal `manifest.sha256`, otherwise the file is rejected as damaged or tampered.
5. Counts are re-checked and the log chain verified; mismatches show as warnings in the preview before anything is written.

## Merging (a restore never duplicates)

* Same book (`bookId` equal, or an empty phone): rows merge **by id** — an existing id is updated only when the backup's copy
  differs; missing ids are added.
* Another book: customers match by phone, then by normalised name; an entry is skipped when that customer already has one with
  the same date, type, amount and transfer number.
* Before merging, the current book is snapshotted into «السجل» as a `BACKUP_RESTORE` row, so the restore itself can be undone.
* On a fresh install the backup's log is adopted whole (chain verified first); otherwise it stays inside the restore record.
