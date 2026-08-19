# Kadr

Android üçün öz serverində saxlanan foto və video yedəkləmə — arxasında öz ev
şəbəkəndəki server dayanır. Bulud yoxdur, abunə yoxdur, hesab yoxdur. Tam
spesifikasiya layihə konteksti sənədindədir (§1–§17).

```
galery app/
├── server/          Node 24 + Fastify + SQLite  —  M1 tamamlanıb ✅
└── android/         Kotlin + Compose            —  M2 tamamlanıb ✅
```

## §14-ə görə vəziyyət

| # | Mərhələ | Vəziyyət |
|---|---|---|
| M1 | Server skeleti | **Hazır** — 94 curl yoxlaması yaşıl, üstəlik hardening və yükləmə-ortası restart dəstləri |
| M2 | Android indeksi | **Hazır** — API 37 cihazında yoxlanılıb: skan → Room → yükləmə → bayt-bayt eyni gedər-gəlmə |
| M3 | Yedəkləmə mühərriki | **Demək olar hazır** — dövr, paketlənmiş dedupe, təkrar cəhdlər və bildiriş işləyir və sınanıb; yenidən başladıqdan sonra davam etmə isə imzalanmayıb (aşağıya bax) |
| M4 | Qalereya interfeysi | **Hazır** — yerli və server zaman xətti birləşdirilib, ay ayırıcıları, zoom-lu baxış, səhifələmə və sürüşdürüb bağlama, paylaşılan element keçidləri |
| M5 | Video | **Yazılıb, imzalanmayıb** — pleyer, keş, jestlər və şəbəkə önizləməsi qurulub, amma emulyatorun proqram dekoderi heç nə oxuda bilmir; oxutma real cihaz tələb edir |
| M6 | Cilalama | **Hazır** — Yedəkləmə statusu, Parametrlər və Zibil qutusu ekranları, server təsdiqi ilə yer boşaltma, haptika, Material You seçimi, hərəkəti azaltma |
| M7 | Möhkəmləndirmə | **Hazır** — dolu disk əvvəlcədən rədd edilir, hər iki tərəfdə miqrasiya testləri, 10 000 asset-lik yük testi yaşıl |

## İndiyə qədər yoxlanılanlar

- Canlı API-yə qarşı 94 `curl` yoxlaması: giriş, resume-lu parçalı yükləmə,
  idempotent təkrar göndərmələr, hash uyğunsuzluğu, dedupe, `Range`
  düzgünlüyü, zibil qutusunun gedər-gəlməsi, albomlar, ləğvetmə.
- Server yükləmənin ortasında öldürülməyə tab gətirir və yalnız SQLite sətri
  ilə yarımçıq fayl əsasında davam edir.
- 256 MB-lıq yükləmə zamanı pik server yaddaşı: **83 MB RSS** (§15 büdcəsi:
  300 MB).
- Cihazda: MediaStore skanı Room-u doldurur, təkrar skan heç nə əlavə etmir,
  5 MB-lıq fayl parçalarla yüklənir və geri endirilən baytların hash-i eynidir.
- §17-nin beş uğursuzluq halı skriptləşdirilmiş serverə qarşı keçir: parçanın
  ortasında qırılan bağlantı, server restartı, hash uyğunsuzluğu, təkrar fayl,
  dolu disk. Dolu disk qalan hər faylı uğursuz etmək əvəzinə işi dayandırır və
  heç bir şəklin altı cəhdindən birinə belə başa gəlmir.
- Cihaz tokeni tətbiqin öz fayllarında oxunmur, soyuq başlanğıcdan sonra sağ
  qalır və istifadəçi çıxış edəndə Keystore açarı ilə birlikdə yox olur.
- 216 fayllıq paket WorkManager üzərindən, cihazın yenidən başlaması da daxil
  olmaqla, təkrarsız şəkildə uçdan-uca işlədi.
- Yer boşaltma serverin zəmanət verə bilmədiyi faylı silməkdən imtina edir və
  fayl hələ diskdə ikən heç nəyi boşaldılmış kimi işarələmir.
- Zibil qutusunun gedər-gəlməsi tətbiqdən idarə olundu: serverdə silinən asset
  29 günlük geri sayımla görünür, telefondan bərpa etmək isə zibil qutusunu
  boşaldır.
- **Real API üzərindən 41 saniyəyə 10 000 asset yükləndi**, pik server yaddaşı
  300 MB büdcəyə qarşı 184 MB, bütün kitabxananın tam delta sinxronizasiyası
  144 ms.
- v1 bazası v2, v3 və v4-ə yüksəlişdən sətirləri toxunulmaz çıxır.
- Zaman xətti səhifə-səhifə oxunur və eyni çəkiliş vaxtını bölüşən şəkillərlə
  dolu kitabxananı səhifələmək onların hər birini dəqiq bir dəfə göstərir —
  `LIMIT/OFFSET` pəncərəsinin səssizcə sətir itirdiyi və ya təkrarladığı hal.

## Yük testinin tapdığı

10 000 asset-də delta sinxronizasiya 9 995 sətir qaytardı — müştərinin heç vaxt
görməyəcəyi beş şəkil, jurnalda heç bir iz, qaldırılmış heç bir xəta yoxdur.
Eyni millisaniyə içində tamamlanan asset-lər eyni `updated_at` dəyərini bölüşür
və səhifə sərhədinə düşən qrup `WHERE updated_at > cursor` tərəfindən qismən
atlanırdı. İndi kursor ciddi şəkildə monotondur, yəni bərabərlik mümkün deyil;
§9-un müqaviləsi dəyişməyib. Təfərrüat: [server/README.md](server/README.md).

## İmzalanmayanlar

İki şey qurulub, amma sübut olunmayıb və hər ikisi eyni şeyə bağlıdır — real
telefona:

**M3, yenidən başladıqdan sonra davam etmə.** Room vəziyyəti də, periodik
cədvəl də sağ qalır, boot qəbuledicisi də davam etməni növbəyə qoyur, amma
tətbiq açılana qədər heç nə yüklənmir. Bunu araşdırarkən üç real qüsur tapılıb
düzəldildi; qalan səbəb müəyyən edilməyib.

**M5, video oxutma.** Emulyatorun proqram H.264 dekoderi həm yerli, həm də
şəbəkə kliplərində ExoPlayer altında uğursuz olur. Klip ExoPlayer-dən kənarda
normal dekodlanır və server autentifikasiyalı `Range` sorğularını düzgün
qarşılayır, yəni sınanmamış qalan şey aparat dekoderində oxutmadır.

Təfərrüat: [android/README.md](android/README.md#known-gaps).

## Qəbul edilmiş qərarlar (§16)

| # | Sual | Cavab |
|---|---|---|
| 1 | Compose, yoxsa XML | **Compose** — istifadədədir |
| 2 | Go, yoxsa Node | **Node 24 + Fastify** — seçildi ki, API dərhal dev maşınında qurulub curl ilə yoxlana bilsin |
| 5 | Diskdə şifrələmə | v1 üçün adi bloblar; diskin özündə LUKS |

§16-dan kənarda qaldırılıb və indi həll olunub: `androidx.security.crypto`
köhnəlib, ona görə cihaz tokeni öz Keystore əsaslı örtüyümüzə köçdü. Yalnız
token şifrələnir və artıq cütləşmiş telefonlar yenidən giriş etmədən keçid edir.

**§16.6, albom modeli qərarlaşdırılıb**: əl ilə yaradılan, serverdə saxlanan və
giriş edən hər telefon tərəfindən bölüşülən albomlar — §16-nın kitabxanaya
verdiyi forma ilə eyni. Telefon qovluqlarını güzgüləmək variantı nəzərdən
keçirilib və rədd edilib: `relativePath` heç vaxt serverə çatmır, köhnə
asset-ləri geriyə doldurmaq mümkün olmazdı, və yerli nüsxəsi boşaldılmış şəkil
öz qovluğundan yox olardı. Hər iki yarı qurulub: albomları server saxlayır,
tətbiq isə onları öz iki delta axını ilə güzgüləyir.

Hələ açıq qalanlar: disk tutumu (§16.3), telefonların sayı (§16.4).

## Başlamaq

```bash
cd server && npm install && npm start
```

Sonra tətbiqi qur və quraşdır — bax: [android/README.md](android/README.md).
Buraxılış build-i üçün özünün yaratdığı imza açarı lazımdır; bağlantı yerindədir
və [orada sənədləşdirilib](android/README.md#signing-a-release).
Server təfərrüatları və API arayışı: [server/README.md](server/README.md).
