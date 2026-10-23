# 库不混淆；consumer 也无需保留任何符号（核心类都被 app/phone 直接引用，R8 会按需保留）
-dontobfuscate
