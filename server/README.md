# Kadr Server

Öz serverində saxlanan foto və video yedəkləmə — layihənin server yarısı.
Node 24 + Fastify + SQLite; nativ modul yoxdur, transkodlaşdırma yoxdur, bulud
yoxdur.

Layihə sənədinin §9 bölməsindəki API müqaviləsini həyata keçirir. **M1 tamamlanıb
və yoxlanılıb** (§14): `curl` ilə cütləşmək, yükləmə seansı açmaq, parçaları
göndərmək, seansı bağlamaq və eyni baytları geri endirmək mümkündür.

---

## Niyə məhz bu quruluş

Hədəf maşın iki nüvəli Sandy Bridge Pentium-dur — 4 GB RAM və adi HDD.
Aşağıdakı hər qərar məhz bundan doğur:

| Qərar | Səbəb |
|---|---|
| `better-sqlite3` yox, daxili `node:sqlite` | Serverdə C++ alət zənciri yoxdur, kompilyasiya addımı yoxdur, Node yeniləndikdən sonra yenidən qurma tələb olunmur. |
| Yükləmə parçaları birbaşa diskə axır | 2 GB-lıq video heç vaxt RAM-a düşmür. Ölçülmüş pik: 256 MB qəbul edilərkən **83 MB RSS**. |
| `Range` orijinal baytlardan verilir | İşi telefonun aparat dekoderi görür; Pentium sadəcə faylı oxuyur. |
| `nice -n 19` ilə tək bir ffmpeg işçisi | Thumbnail-lər nə iki nüvə, nə də disk başlığı uğrunda API ilə yarışmamalıdır. |
| WAL + `synchronous=NORMAL` | Tək yazıcı var, çökməyə qarşı kifayət qədər davamlıdır və yavaş diskdə xeyli az fsync deməkdir. |
| `last_seen_at` cihaz başına dəqiqədə bir yazıya məhdudlaşdırılıb | HDD-ni sorğunun isti yolundan kənarda saxlayır. |

---

## Tələblər

- Node **22.5+** (24 tövsiyə olunur) — `node:sqlite` onunla birlikdə gəlir
- `PATH`-də `ffmpeg` — yalnız thumbnail üçün; qalan hər şey onsuz da işləyir
- `/srv/kadr`-a quraşdırılmış disk

## İşə salmaq

```bash
npm install
npm start
```

İlk hesabı konsoldan yarat — qəsdən internetə açılmayan bir maşında bu, veb
formadan gəlməməlidir (§13):

```bash
node src/cli.js user add hasan
```

### Konfiqurasiya

Hamısı istəyə bağlıdır, hamısı mühit dəyişənidir:

| Dəyişən | Susmaya görə | Qeyd |
|---|---|---|
| `KADR_DATA_DIR` | `/srv/kadr` (Windows-da `./data`) | Bloblar, thumbnail-lər, zibil qutusu, baza |
| `KADR_DB_PATH` | `$KADR_DATA_DIR/kadr.db` | |
| `KADR_HOST` | `0.0.0.0` | Qarşısında Caddy dayananda `127.0.0.1` qoy |
| `KADR_PORT` | `8787` | |
| `KADR_MIN_FREE_BYTES` | 1 GiB | Boş saxlanılan ehtiyat; bu ehtiyatı yeyəcək seans bir bayt belə göndərilməmiş `507` ilə rədd edilir |
| `KADR_TRUST_PROXY` | `loopback` | Kimin `X-Forwarded-For`-una inanmaq lazımdır. Bax: "Proxy arxasında" |

---

## Testlər

Hər iki dəst real HTTP səthini `curl` ilə sürür — heç bir mock yoxdur.

```bash
bash test/e2e.sh
```

94 yoxlama: giriş və kilidlənmə, token ləğvi, resume dəstəkli parçalı yükləmə,
idempotent təkrar göndərmələr, aralıq boşluqları, hash uyğunsuzluğu, qısa
parçalar, dedupe, `Range` düzgünlüyü, zibil qutusunun gedər-gəlməsi, albomlar.

```bash
bash test/restart.sh
```

Serveri təcrid olunmuş portda parçalar arasında öldürür və yükləmənin yalnız
SQLite sətri ilə `.part` faylı əsasında davam etdiyini sübut edir.

```bash
bash test/hardening.sh
```

16 yoxlama: dolu disk bir bayt göndərilməmiş rədd edilir və arxasında yarımçıq
fayl qoymur, yer açılan kimi eyni yükləmə uğur qazanır, baza yenidən açılanda
miqrasiyalar təkrar işləmir, və giriş məhdudlaşdırıcısı bir ünvanı kilidləyərkən
digərinə toxunmur.

```bash
node test/soak.mjs          # 10 000 asset
COUNT=1000 node test/soak.mjs
```

§15-in yük testi. Real API üzərindən real yükləmələr — check, seans, parça,
complete — yaddaşı, gecikməni və bazanın böyüməsini izləyərək.

### 10 000 asset-də vəziyyət

| | |
|---|---|
| Yükləmə sürəti | saniyədə 246 asset (10 000 üçün 41 s) |
| Pik RSS | 300 MB büdcəyə qarşı **184 MB** |
| Baza | 7.8 MB |
| 500 hash-lik dedupe yoxlaması | 5–6 ms |
| İlk sinxronizasiya səhifəsi | 7 ms |
| Tam delta sinxronizasiya | 21 səhifədə 144 ms |

Altı yükləmə eyni anda gedir — bu, bir telefonun edəcəyindən çoxdur. Rəqabət
qəsdən yaradılıb və aşağıdakı səhifələmə qüsurunu məhz o tapıb.

---

## API-yə qısa baxış

Əsas yol: `/api/v1`. Cavablar ya `{ "data": ... }`, ya da
`{ "error": { "code", "message" } }` şəklindədir.

### Giriş

```bash
curl -X POST localhost:8787/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"hasan","password":"…","deviceName":"Pixel 8"}'
```

Giriş edən hər kəs eyni kitabxananı görür (§16). Parol burada cihaz tokeninə
dəyişdirilir və bir daha göndərilmir. Bir ünvandan beş səhv parol 15 dəqiqəlik
kilid yaradır — Caddy qarşıda duranda "ünvan"ın nə demək olduğu üçün "Proxy
arxasında" bölməsinə bax.

### Göndərməzdən əvvəl soruş

```bash
curl -X POST localhost:8787/api/v1/assets/check \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"hashes":["<sha256>"]}'
```

### Yükləmə

```bash
# 1. seans aç (uploadId qaytarır; blob tanışdırsa alreadyExists)
curl -X POST localhost:8787/api/v1/uploads \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sha256":"…","sizeBytes":9961472,"filename":"clip.mp4","mimeType":"video/mp4"}'
```

```bash
# 2. parça göndər (təkrarla; artıq tutulmuş aralığı yenidən göndərmək təsirsizdir)
curl -X PATCH "localhost:8787/api/v1/uploads/$ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  -H 'Content-Range: bytes 0-4194303/9961472' \
  --data-binary @chunk0.bin
```

```bash
# 3. çökmədən sonra harada qaldığını soruş
curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/uploads/$ID"
```

```bash
# 4. möhürlə — server yenidən hashlayır və uyğunsuzluğu rədd edir
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "localhost:8787/api/v1/uploads/$ID/complete"
```

### Kitabxana

```bash
curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/assets?since=0&limit=500"
```

```bash
curl -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=0-1048575' \
  -o head.bin "localhost:8787/api/v1/assets/$ASSET/file"
```

### Albomlar (§16.6)

Əl ilə yaradılan və paylaşılan: §16 kitabxananı onsuz da paylaşılan etdi, ona
görə yalnız bir telefonda yaşayan albom aid olduğu kitabxana ilə ziddiyyət
təşkil edərdi.

```bash
# yarat, doldur və geri oxu
curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Georgia 2024"}' "localhost:8787/api/v1/albums"

curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"assetIds\":[\"$ASSET\"]}" "localhost:8787/api/v1/albums/$ALBUM/items"

curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/albums?since=0"
curl -H "Authorization: Bearer $TOKEN" "localhost:8787/api/v1/album-items?since=0"
```

Bir yox, iki delta axını. Albomlar və üzvlük tamamilə fərqli sürətlərlə dəyişir
— albomun adını dəyişmək beş min üzvlük sətrini şəbəkə üzərindən sürükləməməlidir
— və hər biri öz `updated_at` sayğacını saxlayır, ona görə bir yükləmə bütün
albom kursorlarını irəli itələmir.

"Albomun məzmunu" adlı endpoint qəsdən yoxdur. Müştərilər onsuz da kitabxananı
güzgüləyir, `album-items` isə onlara əlaqəni verir; məzmun isə yerli olaraq edə
biləcəkləri bir birləşdirmədir. Zibil qutusuna ayrıca endpoint yalnız ona görə
lazım oldu ki, delta sinxronizasiya oraya tombstone-dan başqa heç nə daşımır.

Şəkli albomdan çıxarmaq sətri silmir, ona **tombstone** qoyur (`removed: true`)
— eyni səbəbdən ki, asset-lərə də tombstone qoyulur: sadəcə yox olmuş sətir heç
bir müştərinin görə biləcəyi dəyişiklik deyil, ona görə şəkil artıq sinxronlaşmış
hər telefonda o albomda əbədi qalardı. Geri əlavə etmək isə açar münaqişəsi
yaratmır, tombstone-u təmizləyir.

**Asset**-i silmək onun albom sətirlərinə toxunmur, ona görə zibil qutusundan
bərpa etmək şəkli əvvəlki albomlarına qaytarır. Yerli yer boşaltmaq üzvlüyə
ümumiyyətlə toxunmur — o, telefon tərəfindəki silmədir və fayl hələ də
serverdədir.

### Proxy arxasında

Caddy TLS-i özündə bitirir və `127.0.0.1`-dən proxy edir, ona görə API-yə kimin
`X-Forwarded-For`-una inanacağı deyilməlidir — əks halda hər sorğu eyni ünvandan
gəlmiş kimi görünür və §13-ün IP başına giriş məhdudlaşdırıcısı tək bir qlobal
sayğaca çevrilir, orada isə bircə səhv parol evdəki bütün telefonları kilidləyər.

`KADR_TRUST_PROXY` susmaya görə `loopback`-dir: yalnız bu maşındakı proxy-yə
inanılır. Qəsdən `true` deyil — API nə vaxtsa birbaşa əlçatan olarsa, LAN-dan
gələn başlıq ünvanı saxtalaşdıra bilməməlidir. `hardening.sh` §4 hər iki yarını
sabitləyir: bir ünvan kilidlənir, digəri toxunulmamış qalır.

### Xəta kodları

| Kod | Status | Mənası |
|---|---|---|
| `ALBUM_DELETED` | 410 | Albom tombstone-lanıb; redaktə edilə bilməz |
| `TOO_MANY` | 400 | Bir albom sorğusunda 500-dən çox asset |
| `RANGE_GAP` | 409 | Parça `receivedBytes`-dan sonra başlayır; oradan davam et |
| `SESSION_RESET` | 409 | Yarımçıq fayl yoxa çıxıb; sıfırıncı baytdan başla |
| `LENGTH_MISMATCH` | 400 | `Content-Range`-in vəd etdiyindən az bayt gəldi |
| `HASH_MISMATCH` | 409 | Yığılmış faylın hash-i səhvdir; seans sıfırlanır |
| `INCOMPLETE` | 409 | Bütün baytlar gəlməmiş `complete` çağırılıb |
| `DISK_FULL` | 507 | `ENOSPC` — üzə çıxarılır, heç vaxt səssiz donma deyil |
| `LOGIN_LOCKED` | 429 | Bu IP-dən beş uğursuz giriş cəhdi |
| `THUMB_UNAVAILABLE` | 503 | ffmpeg yoxdur, ya da kadr çıxarıla bilmədi |

---

## Yük testinin tapdığı qüsur

10 000 asset-də delta sinxronizasiya **9 995 sətir** qaytardı. Müştərinin heç
vaxt görməyəcəyi beş şəkil.

`GET /assets?since=X` səhifələməni `WHERE updated_at > ? ORDER BY updated_at`
ilə edir və kursor sonuncu sətrin `updated_at` dəyəridir. Eyni millisaniyə
ərzində tamamlanan bir neçə asset eyni zaman möhürünü bölüşür; belə bir qrup
səhifə sərhədinə düşəndə `> cursor` onun qalan hissəsini atlayır. Nə xəta baş
verir, nə də jurnala nəsə düşür — kitabxana sadəcə səssizcə əskik olur.

Düzəliş §9-un müqaviləsini toxunulmaz saxlayır. `updated_at` indi
`nextUpdatedAt()` tərəfindən `max(now, ən yüksək + 1)` kimi verilir, yəni
bərabərlik mümkün deyil və sütun hələ də §8-in dediyi mənanı daşıyır. Yük testi
tam səhifələmənin dəqiq yüklənən qədər sətir qaytardığını yoxlayır — qüsuru tutan
məhz bu yoxlamadır.

## Bilməyə dəyən iki davranış

**Zibil qutusundakı asset itkin sayılır.** `/assets/check` yalnız canlı
asset-ləri mövcud kimi bildirir. Yumşaq silinmiş asset mövcud kimi göstərilsəydi,
telefon onu `VERIFIED` işarələyib yerli yeri boşalda bilərdi və zibil qutusu 30
gündən sonra təmizlənəndə fayl birdəfəlik itərdi. Zibil qutusundakı asset-i
yenidən yükləmək ikinci sətir yaratmır, mövcud sətri bərpa edir.

**`complete` heç nəyə inanmayan yeganə şeydir.** Müştərinin bildirdiyi hash o
vaxta qədər sadəcə iddiadır ki, server yığılmış faylı yenidən oxuyub özü
hashlasın. Uyğunsuzluq pis blob yazmaq əvəzinə seansı sıfırlayır.

---

## Ubuntu-da yerləşdirmə

Bu reponu serverdə klonla və quraşdırıcını işə sal:

```bash
sudo bash server/deploy/install.sh
```

Skript Node, ffmpeg və Caddy quraşdırır, `kadr` servis hesabını yaradır, tətbiqi
`/opt/kadr/server`-ə köçürür, systemd unit-ini quraşdırır, Caddyfile yazır,
443-ü açır və sonda servisin doğrudan cavab verdiyini yoxlayır. Təkrar
işlədilməsi yerində yeniləmə edir; `/srv/kadr`-a — şəkillərin yaşadığı yerə —
heç vaxt toxunmur.

`KADR_SITE=photos.lan` Caddy-nin xidmət etdiyi host adını, `KADR_DATA_DIR` isə
məlumat qovluğunu əvəz edir.

**Node.** Quraşdırıcı 22.13-dən köhnə hər şeyi rədd edir və əvəzinə 24.x
quraşdırır. Bütün baza qatı `node:sqlite` üzərində qurulub, bu versiyadan aşağı
22.x buraxılışları isə onu `--experimental-sqlite` arxasında saxlayır — unit isə
o bayrağı ötürmür. Yəni köhnəlmiş Node zəifləyərək işləmir, sadəcə heç qalxmır.

Skriptin qəsdən insana buraxdığı və sonda ekrana yazdığı üç şey: ilk hesabın
yaradılması (parol terminaldan yazılır), host adının maşına yönləndirilməsi və
Caddy-nin kök sertifikatının telefona quraşdırılması.

Əl ilə etmək istəsən:

```bash
sudo cp deploy/kadr.service /etc/systemd/system/
sudo systemctl enable --now kadr
journalctl -u kadr -f          # qalxmasını izlə
```

TLS Caddy-də bitir (bax: `deploy/Caddyfile`) — unit-in Kadr-ı `127.0.0.1`-ə
bağlamasının səbəbi budur. Bunu açıq internetə port-forward etmə; kənardan
Tailscale və ya WireGuard üzərindən çat (§13).

---

## Hələ edilməyib

- `GET /assets/{id}/thumb` işləyir, amma yaradılma üçün `ffmpeg` quraşdırılmış
  olmalıdır; aşağı prioritetli fon ön-yaratma işçisi və onun `--dry-run` rejimi
  (§17) qurulmayıb. Thumbnail-lər ilk sorğuda tənbəl şəkildə yaradılır.
- Server ünvanı üçün hələ QR səhifəsi yoxdur; ünvan əl ilə yazılır (§12,
  onboarding).
- Proses daxilində TLS yoxdur; qarşıda Caddy-nin dayanacağı gözlənilir.
