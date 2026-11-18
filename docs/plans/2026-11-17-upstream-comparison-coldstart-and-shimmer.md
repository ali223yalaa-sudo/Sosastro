# مقارنة مع `vitorpamplona/amethyst` — بطء أول تشغيل + الشيمّر→الفراغ

**التاريخ:** 2026-11-17
**الفرع:** `main` @ `e120e63`
**المرجع:** `vitorpamplona/amethyst` (main، آخر تحديث 2026-11-17، آخر إصدار v1.21.0)
**القيد:** ممنوع حذف أي شيء. هذه الوثيقة تشخيص ومقارنة فقط — لا تغييرات كود.

---

## ٠. سيرفر الهدايا المحلي — الحالة: يعمل (لا حاجة لتشغيل)

| الفحص | النتيجة |
|---|---|
| العملية | `server.py --port 12000 --also-port 12001` (PID 308) |
| الـ watchdog | `bash ./watchdog.sh` (PID 359) |
| `127.0.0.1:12000` | 200 |
| `127.0.0.1:12001` | 200 |
| `work-1-...all-hands.dev` | 200 |
| `work-2-...all-hands.dev` | 200 |
| `data/` | `catalog.json`, `previews/`, `published.json`, `assets/` سليمة |

**لم يُحذف شيء.** لا حاجة لإعادة تشغيل.

**فراغ واحد مكتشف (غير عاجل):** لا cron ولا systemd unit يسجّل `watchdog.sh`. إذا أُعيد تشغيل الحاوية، لن يعود السيرفر تلقائياً. (`ss`/`netstat` غير متاحين في البيئة.)

---

## ١. السبب الجذري لبطء أول تشغيل (عندنا)

سلسلة الأدلة بالكود:

1. **ريلاي واحد فقط** — `commons/.../defaults/Constants.kt:35`
   `amethystHome = "wss://astro-relay.hmzzhproxy29127.workers.dev/"`
   مع `TEMP_HOME_RELAY_ONLY = true` (سطر 72).

2. **نفس الريلاي هو ريلاي الرسائل الخاصة (DM)** — `commons/.../defaults/AmethystDefaults.kt:56`
   `val DefaultDMRelayList = listOf(Constants.amethystHome)`
   ويُثبَّت عند إنشاء الحساب — `amethyst/.../ui/screen/AccountSessionManager.kt:338`
   `backupDMRelayList = bootstrap.dmRelayList`

3. **تصنيف Tor للـ DM مفعّل افتراضياً** — `commons/.../tor/TorRelaySettings.kt:26`
   `dmRelaysViaTor: Boolean = false` (في الـ data class)
   لكن المحفوظ فعلياً — `amethyst/.../model/preferences/TorSharedPreferences.kt:103`
   `dmRelaysViaTor = preferences[DM_RELAYS_VIA_TOR_KEY] ?: true` ← **true**

4. **قاعدة التصنيف** — `commons/.../tor/TorRelayEvaluation.kt`
   لأن الريلاي في `dmRelayList` ← النتيجة `dmRelaysViaTor = true`.
   أي: الريلاي الوحيد **محكوم عليه بالمرور عبر Tor دائماً**.

5. **بوّابة المنع** — `amethyst/.../AppModules.kt:493`
   `canDial = { url -> !shouldUseTorForRelay(url) || torManager.isSocksReady() }`

6. **لا اتصال قبل جهوز Tor** — `commons/.../relayClient/BasicRelayClient.kt:118`
   `if (!socketBuilder.canConnect(url)) return`

7. **زمن إقلاع Tor** — `TorManager`: `BOOTSTRAP_TIMEOUT_MS = 60s`، `SELF_HEAL_AFTER_MS = 45s`.
   المشروع المرجعي قاس Arti فعلياً: **12.6–51.7s** لتنزيل الدليل.

**النتيجة:** التطبيق كله (فيد + رسائل + إشعارات + بحث) ينتظر إقلاع Tor قبل أول اتصال. هذا يفسّر: التأخر عند التثبيت الجديد + الفراغ + تأخر الرسائل. **كلها سبب واحد.**

---

## ٢. الاكتشاف الأهم: الإصلاح الرسمي لـ upstream **لا ينطبق علينا**

upstream أصلح هذه المشكلة بالضبط في الالتزام `18b0dafec` (2026-08-27):
**"feat(tor): route the app's stand-in relays like the user's own until their lists arrive"**

قياساتهم على SM-T220 (نفس الحساب، تثبيت نظيف):

| المؤشر | قبل | بعد |
|---|---|---|
| أول socket ريلاي | `login+5.87s / +7.89s` (دائماً 2.3–2.9s **بعد** جهوز Tor) | `login+1.21s / +1.24s / +1.29s` (**مستقل عن Tor**) |
| أحداث عند عدّاد 20s | 0 / 892 / 1159 / 2590 | 3719 / 3997 / 4081 / 5311 / 6051 |

**آلية إصلاحهم:** تصنيف جديد `assumed` يوضع **فوق** الفرع الأخير `newRelaysViaTor` مباشرة، ويحلّ إلى `trustedRelaysViaTor` (افتراضيه false). الملفات: `AssumedRelayListsState.kt` (جديد)، `TorRelayEvaluation.kt`، `Nip65RelayListState.kt`، `TorRelayState.kt`، `AccountsTorStateConnector.kt`.

### لماذا لا ينطبق علينا؟

سلسلة الأسبقية في `useTor()` عندهم:

```
torType == OFF        -> false
isLocalHost           -> false (أو newRelaysViaTor لغير المملوك)
isOverlayNetwork      -> false
isOnion               -> onionRelaysViaTor
in moneyOpRelayList   -> moneyOperationsViaTor
in dmRelayList        -> dmRelaysViaTor      <-- ريلاينا هنا
in trustedRelayList   -> trustedRelaysViaTor
in assumedRelayList   -> trustedRelaysViaTor  <-- إصلاحهم
else                  -> newRelaysViaTor
```

**ريلاينا `amethystHome` داخل `dmRelayList`.** فرع `dm` **يسبق** `assumed`. لذلك `assumed` لا يلتقط ريلاينا أبداً — **إصلاحهم no-op بالنسبة لنا.**

وupstream أنفسهم حذّروا في خطة الإصلاح (`amethyst/plans/2026-08-26-bootstrap-relays-skip-tor-until-user-data.md`):

> "If the gain is materially smaller, the likely cause is that the feed is gated on outbox-discovered relays (which stay 'new', hence Tor) rather than the user's own list."

وحالتنا أشد: كل شيء يتقارب إلى ريلاي واحد، وهو نفسه ريلاي DM.

**أيضاً:** `assumed` عندهم = `bootstrapInbox + eventFinderRelays` فقط (nip65 + search + indexer). لا يشمل DM. وحتى لو شمل، فرع `dm` يتقدّم.

### فرق ثانٍ مكتشف: افتراضي `newRelaysViaTor` المحفوظ

| | عندنا | عندهم (commons `TorSettingsFlow`) |
|---|---|---|
| `newRelaysViaTor` | `?: true` (محفوظ) — `TorSharedPreferences.kt:104` | `false` |
| `dmRelaysViaTor` | `?: true` | `true` |
| `trustedRelaysViaTor` | `?: false` | `false` |
| `torType` | `INTERNAL` | `INTERNAL` |

`newRelaysViaTor` المحفوظ عندنا = **true**، أي أشد من upstream. يجب التحقق: محاولة جلب `TorSharedPreferences.kt` من upstream أعادت 404 (الملف عندهم في مكان آخر أو مُعاد هيكلته).

---

## ٣. مسألة تعدد الريلايات لكل شاشة — الجواب: البنية **متطابقة**

سؤالك: هل لكل شاشة ريلاي خاص؟ **نعم، في upstream ونسختنا بنفس الشكل.**

الحالة في `Account.kt`:

| الحالة | عندنا | عندهم |
|---|---|---|
| `homeRelays` | ✅ | ✅ |
| `outboxRelays` | ✅ | ✅ |
| `dmRelays` | ✅ | ✅ |
| `notificationRelays` | ✅ | ✅ |
| `trustedRelays` | ✅ | ✅ (+ `favoriteRelayList`, `trustProviderList.liveRelays`) |
| `defaultGlobalRelays` | ✅ | ✅ |
| `searchRelayList` / `indexerRelayList` | ✅ | ✅ |
| `assumedRelays` | ❌ | ✅ (جديد) |

مصدر ريلايات كل شاشة (متطابق):

| الشاشة | المصدر |
|---|---|
| الفيد الرئيسي | `HomeOutboxEventsEoseManager` → ريلايات المتابَعين (outbox) |
| الإشعارات | `notificationRelays.flow` (nip65 inbox + local) |
| الرسائل/الـ giftwraps | `dmRelays.flow` |
| البروفايل/المنشورات | `homeRelays.flow` |
| البحث | `searchRelayList` |
| الشطرنج | `defaultGlobalRelays` + `notificationRelays` + `outboxRelays` |
| البحث العام/الفيدات | `defaultGlobalRelays` |

**لكن في وضع الجزيرة (`TEMP_HOME_RELAY_ONLY = true`)**، دالة `Constants.homeRelayOnlyMap()` تُسقط **كل** المجموعات إلى `amethystHome` واحد:

```kotlin
fun homeRelayOnlyMap(filters: Map<...>) =
    if (!TEMP_HOME_RELAY_ONLY || filters.isEmpty()) filters
    else mapOf(amethystHome to filters.values.flatten().distinct())
```

**الخلاصة:** لا نحتاج ريلايات أكثر. التعدد موجود بنيوياً. المشكلة ليست عدد الريلايات — بل **تصنيف Tor للريلاي الواحد المملوك**. الفصل حسب الغرض يصبح بلا معنى عندنا لأن كل شيء يتقارب إلى ريلاي واحد مملوك.

---

## ٤. الشيمّر → الفراغ

### عندنا
`commons/.../ui/feeds/FeedContentState.kt`
- سطر 48: `_feedContent = MutableStateFlow<FeedState>(FeedState.Loading)` ← الشيمّر
- سطر 119–120: `if (notes.isEmpty()) _feedContent.tryEmit(FeedState.Empty)` ← الفراغ
- **لا يوجد إشارة EOSE / "انتهى التحميل"** في طبقة الفيد.

### عندهم
نفس المنطق تماماً (`FeedContentState.kt` سطر 234–235: `if (notes.isEmpty()) _feedContent.tryEmit(FeedState.Empty)`). **upstream لم يُصلح هذا.**

لكن upstream أضاف أشياء ذات صلة:
- `onResume()` مع `refreshOnResume` + إعادة تحميل عند الاستئناف، و`WatchLifecycleAndUpdateModel` يستخدم `LaunchedEffect` + `LifecycleResumeEffect`.
  عندنا: `WatchLifecycleAndUpdateModel` يستدعي `invalidateData(true)` مباشرة.
- **اختبارات سباق جديدة:** `commons/src/jvmTest/.../feeds/FeedContentStateRaceTest.kt` (142 سطراً) و`LazyFeedContentStateTest.kt` (267 سطراً). لا توجد عندنا.

**القيمة:** نقل هذين الاختبارين يعطينا شبكة أمان لإصلاح الشيمّر بشكل صحيح، بدل التخمين.

### التصميم المطلوب للإصلاح (بدون كسر اختبارات desktop)
اختبارات desktop عندنا تؤكد `FeedState.Empty`. لذلك الإصلاح يجب أن يضيف **إشارة "انتهت أول محاولة تحميل"** (EOSE أو أول نتيجة/مهلة) بحيث:
- قبل الإشارة: `Loading` (شيمّر) حتى لو كانت القائمة فارغة.
- بعد الإشارة: `Empty` مسموح.

---

## ٥. ما نحتاجه لحل مشاكل المستخدمين بدون تأخير

### P0 — أكبر مكسب، أقل تكلفة (التأخر 5–50s عند أول تشغيل)
**إعفاء ريلاي الجزيرة المملوك من بوّابة Tor.**
- المنطق: `amethystHome` ريلاي **مملوك للمشغّل**، وهو الوحيد. Tor لا يضيف خصوصية ضد مشغّل ريلاي المستخدم نفسه، وفرضه يعني انتظار 12–51s بلا مقابل.
- الشكل المقترح: مجموعة ثابتة `OWNED_ISLAND_RELAYS` تُفحص في `useTor()` (أو في `canDial`) وتُعيد `false` مباشرة، مع تعليق يوضح السبب.
- أثر جانبي: **يجب ألا يتسرب أي ريلاي غير مملوك** إلى هذا الإعفاء (اقتصر على قائمة صريحة، لا على "أي ريلاي في nip65").
- حماية: اختبار وحدة + التحقق أن `isInMyRelayList` (قرار NIP-42 AUTH) **لا** يرى هذا الإعفاء.

### P0b — توحيد افتراضي `newRelaysViaTor`
- عندنا محفوظ `true`، وعند upstream `false`. يجب الحسم: إما الموافقة على `false` (متوافق مع منطق `assumed`) أو توثيق سبب `true`.
- **يحتاج قراراً منك** — لأنه يمسّ الخصوصية.

### P1 — إصلاح الشيمّر → الفراغ
- إضافة إشارة "انتهت أول محاولة" في `FeedContentState`، ثم `Empty` بعدها فقط.
- نقل `FeedContentStateRaceTest.kt` و`LazyFeedContentStateTest.kt` من upstream كشبكة أمان.
- ضمان عدم كسر اختبارات desktop التي تؤكد `FeedState.Empty`.

### P2 — نقل آلية `assumed` كاملة (اختياري)
- مفيدة فقط لو أضفنا ريلايات غير الجزيرة مستقبلاً. لا تحلّ حالتنا الحالية (فرع `dm` يسبقها).

### غير عاجل — استمرارية سيرفر الهدايا
- إضافة تسجيل دائم (systemd/cron) أو تحديث `start.sh` ليعيد التشغيل بعد إعادة تشغيل الحاوية.

---

## ٦. المخاطر

| الخطر | التخفيف |
|---|---|
| إعفاء واسع يسرّب ريلايات غير مملوكة خارج Tor | قائمة صريحة واحدة فقط (`amethystHome`) |
| كسر قرار NIP-42 AUTH | الإعفاء في مسار `useTor()`/`canDial` فقط، وليس في `TrustedRelayListsState` — تماماً كما فعل upstream |
| كسر اختبارات desktop | الاختبارات الجديدة تُضاف، والقديم يبقى أخضر |
| تغيير الخصوصية دون علم المستخدم | P0 لا يغيّر خصوصية أي ريلاي **غير** مملوك؛ P0b يحتاج موافقة صريحة |

---

## ٧. حالة الأدلة

- ✅ سيرفر الهدايا يعمل (4 نقاط فحص).
- ✅ السبب الجذري لبطء أول تشغيل مُثبت بالكود + بقياسات upstream.
- ✅ إصلاح upstream الرسمي مُحدَّد (`18b0dafec`) ومُثبت أنه **لا ينطبق علينا**.
- ✅ بنية الريلايات لكل شاشة متطابقة.
- ✅ الشيمّر: المنطق نفسه عندنا وعندهم؛ لم يُصلح عندهم.
- ⚠️ افتراضي `newRelaysViaTor` المحفوظ عند upstream: غير مؤكد (404).
- ✅ `./gradlew :commons:jvmTest --tests "*TorRelayEvaluationTest*"` ينجح (مع الشبكة؛ `--offline` يفشل لنقص تبعيات مخزّنة).

---

## ٨. P0 مُنفَّذ ✅ (2026-11-17)

**التغيير:** إعفاء ريلاي الجزيرة المملوك من بوّابة Tor. 3 ملفات، +68 سطراً، بلا حذف.

| الملف | التغيير |
|---|---|
| `commons/.../defaults/Constants.kt` | +9: `ownedIslandRelays = setOf(amethystHome)` |
| `commons/.../tor/TorRelayEvaluation.kt` | +9: فرع `relay in Constants.ownedIslandRelays -> false` |
| `commons/.../tor/TorRelayEvaluationTest.kt` | +50: 6 اختبارات |

**موضع الفرع:** بعد `isLocalHost` و`isOnion`، وقبل `moneyOp`/`dm`/`trusted`.
- قبل `dm` ضروري: ريلاينا هو نفسه ريلاي DM، وفرع `dm` كان يرسله عبر Tor.
- بعد `isOnion` ضروري: يبقى أي `.onion` تحت `onionRelaysViaTor` كما كان.

**الأثر:** أول اتصال ريلاي لم يعد ينتظر Arti (12.6–51.7s). الفيد والرسائل والإشعارات تفتح مباشرة.

**التحقق:**
- `:commons:jvmTest` → **1201 اختبار، 0 فشل، 0 أخطاء**.
- الاختبارات الستة الجديدة نُفّذت فعلاً (مؤكدة من XML: `tests="26" failures="0"` لملف Tor).
- `:amethyst:compileFdroidDebugKotlin` → **BUILD SUCCESSFUL** (لا تغيير في التوقيع).
- `useTor` يُستخدم للتوجيه الشبكي فقط (`OkHttpWebSocket.Builder`, `canDial`, `TorCircuitHealthTracker`) — **لا يمسّ** قرار NIP-42 AUTH.

**اختبارات تحمي من التسرّب:**
- `ownedExemption_doesNotLeakToOtherRelays` — ريلاي آخر يبقى على سياسة `dm`، ومجهول يبقى على `new`.

---

## ٩. شاشة الدردشة الفردية — الميزات الناقصة مقابل upstream

شاشة الدردشة عندنا **غنية**: ردود، حذف، تحرير، نسخ، حجب، إبلاغ، قراءة صوتية، حوالة، تفاعلات (`ChatReactionChips`)، إيصالات تسليم، ردود مقتبسة، NIP-04 + NIP-17، رفع ملفات مشفّرة.

### ناقص فعلاً (مؤكد بالكود)

| الميزة | عندنا | upstream | ملاحظة |
|---|---|---|---|
| **منتقي الإيموجي** | ❌ لا يوجد في أي مكان | ✅ `unicodeEmoji/` كامل (`EmojiPicker.kt` 921 سطراً، `EmojiPanel.kt` 225، `UnicodeEmojiPalette.kt`) | **الأهم** — يوجد فقط إكمال `:shortcode:` |
| **بوّابة تاريخ الدردشة** | ❌ `ChatHistoryGate.kt` غائب | ✅ `ChatHistoryGate.kt` (238 سطراً) + `ChatHistoryGateCard.kt` | يمنع مسح كل الـgiftwraps؛ يعرض "Keep looking"/"Continue"/"Stop" |
| **تحذير الإبلاغ في DM** | ❌ `DmReportWarningCard.kt` غائب | ✅ 328 سطراً | يظهر عند وجود بلاغات سابقة |
| **الملصقات (Stickers)** | ❌ | ❌ | **لا يوجد عند upstream أيضاً** — لا حاجة للبحث عنها |

### الموجود عندنا (تأكيد إيجابي)
`ChatMessageActionSheet` (536 سطراً) يشمل: رد، تحرير مسودة، حذف، إبلاغ، إضافة وسم، نسخ، مشاركة، حوالة (Zap)، إشارة مرجعية، تفاصيل تسليم لكل ريلاي.

### خلاصة الإجابة على سؤالك
- **الإيموجي:** نعم، ناقص — وupstream يملك وحدة كاملة. أنصح بنقلها (P3).
- **الملصقات:** لا وجود لها في upstream أصلاً.
- **ريلاي خاص لكل شاشة:** لا نحتاج — البنية موجودة، والجزيرة تُسقطها إلى ريلاي واحد. ريلاي واحد يكفي ما دام وضع الجزيرة مفعّلاً.

---

## ١٠. الخطوة التالية (لم تُنفَّذ بعد)

- **P1:** إصلاح الشيمّر→الفراغ (إشارة "انتهت أول محاولة").
- **P3:** نقل `unicodeEmoji` (منتقي الإيموجي) إلى المُحرّر.
- **P2:** نقل `ChatHistoryGate` + `DmReportWarningCard` لشاشة الدردشة.
- **غير عاجل:** تسجيل دائم لـwatchdog سيرفر الهدايا.
