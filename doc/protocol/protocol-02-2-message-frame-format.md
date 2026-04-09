## 2. Message Frame Format

All messages serialized over streams use **JSON** encoded as UTF-8, terminated by a **single `\n` (LF, 0x0A)** byte. This makes each message self-delimiting on the byte stream.

### 2.1 Frame Envelope

Every JSON message carries a `"type"` discriminator:

```jsonc
{
  "type": "<message_type>",
  ...fields...
}
```
