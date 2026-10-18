package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.InputStream
import java.security.MessageDigest

/**
 * Supplies raw streamed catalogue bytes by name. Hash DATs use [PackedDatAssetProvider]; this seam remains
 * for compressed keyed `.idcatgz` catalogues. The app implements both over `context.assets`.
 */
fun interface DatAssetProvider {
    /** Raw catalogue bytes (still gzip-compressed); the caller decompresses. Null = absent. */
    fun openRaw(assetName: String): InputStream?
}

/** One immutable packed DAT shard plus the source-set invariants it reconstructs from the build-time XML. */
data class BundledDatAsset(
    val platformId: PlatformId,
    /** Stable logical source identity retained across physical repacks (for example `snes.datgz`). */
    val sourceId: String,
    /** Physical uncompressed APK asset (`*.midx`). */
    val assetName: String,
    val origin: MetadataOrigin,
    /** Exact value read from the source XML's own `<header><name>` and stamped into the packed header. */
    val expectedHeaderName: String,
    /** Historical source-gzip size retained only for the measured packing comparison. */
    val legacyXmlCompressedSizeBytes: Long,
    /** Exact physical `.midx` bytes; excluded from the semantic catalogue latch. */
    val packedSizeBytes: Long,
    /** Exact number of usable source `<rom>`/`<disk>` rows, before SHA-1 grouping. */
    val expectedEntryCount: Int,
    val expectedUniqueSha1Count: Int,
    val expectedAmbiguousSha1Count: Int,
    /** SHA-256 of the exact decompressed XML fed to the packer. */
    val contentSha256: String,
    /** SHA-256 of canonical lookup-visible SHA/title/collapse semantics. */
    val semanticMapSha256: String,
    /** SHA-256 of the physical packed bytes; packaging/provisioning lock only. */
    val packedSha256: String,
) {
    constructor(
        platformId: PlatformId,
        sourceId: String,
        origin: MetadataOrigin,
        expectedHeaderName: String,
        legacyXmlCompressedSizeBytes: Long,
    ) : this(
        platformId = platformId,
        sourceId = sourceId,
        assetName = bundledPackedDatIntegrity(sourceId).assetName,
        origin = origin,
        expectedHeaderName = expectedHeaderName,
        legacyXmlCompressedSizeBytes = legacyXmlCompressedSizeBytes,
        packedSizeBytes = bundledPackedDatIntegrity(sourceId).packedSizeBytes,
        expectedEntryCount = bundledDatIntegrity(sourceId).expectedEntryCount,
        expectedUniqueSha1Count = bundledPackedDatIntegrity(sourceId).expectedUniqueSha1Count
            ?: bundledDatIntegrity(sourceId).expectedEntryCount,
        expectedAmbiguousSha1Count = bundledPackedDatIntegrity(sourceId).expectedAmbiguousSha1Count,
        contentSha256 = bundledDatIntegrity(sourceId).contentSha256,
        semanticMapSha256 = bundledPackedDatIntegrity(sourceId).semanticMapSha256,
        packedSha256 = bundledPackedDatIntegrity(sourceId).packedSha256,
    )

    internal fun packedExpectation(): PackedDatExpectation = PackedDatExpectation(
        sourceId = sourceId,
        platform = platformId.value,
        origin = origin,
        expectedHeaderName = expectedHeaderName,
        expectedEntryCount = expectedEntryCount,
        expectedUniqueSha1Count = expectedUniqueSha1Count,
        expectedAmbiguousSha1Count = expectedAmbiguousSha1Count,
        expectedPackedSizeBytes = packedSizeBytes,
        inputContentSha256 = contentSha256,
        semanticMapSha256 = semanticMapSha256,
    )
}

private data class BundledDatIntegrity(
    val expectedEntryCount: Int,
    val contentSha256: String,
)

private data class BundledPackedDatIntegrity(
    val assetName: String,
    val packedSizeBytes: Long,
    val semanticMapSha256: String,
    val packedSha256: String,
    val expectedUniqueSha1Count: Int? = null,
    val expectedAmbiguousSha1Count: Int = 0,
)

/** Hardcoded per-blob semantic coverage lock; never derive this table from the payload under test. */
private val bundledDatIntegrityByAsset = mapOf(
    "3ds.datgz" to BundledDatIntegrity(2_163, "715e3ce225a4c6ee5c97fecb1be8635cd5a98e0a41d597e8b17e56641f770fbd"),
    "3ds_dec.datgz" to BundledDatIntegrity(2_161, "2340e021d484f406e36f8a342ff2a41243893a674bcddb4293abb481f6f066a4"),
    "atari2600.datgz" to BundledDatIntegrity(894, "979e8217817550b66d87361465cca398e784f8e14bb8240c91d32be266329ade"),
    "atari7800.datgz" to BundledDatIntegrity(111, "122fa1421213da1a57941fec6db125a86fb128c9443b236255514456f31e576b"),
    "atarilynx.datgz" to BundledDatIntegrity(284, "5a78332c249201d57f81be504cbc78928d3118d930b6aa743db054d4b13f6465"),
    "dreamcast.datgz" to BundledDatIntegrity(1_506, "2955338a8695c630c73748629e71f2911c1c365a6a6559bffc30183d9d63651a"),
    "dreamcast_redump.datgz" to BundledDatIntegrity(12_606, "8b7df38f511d1f06f33eef4f1e4624d024f7adffc69e99f38259c5d597bdcdac"),
    "gamecube.datgz" to BundledDatIntegrity(2_018, "1dec442dbd72f2c288cf4205b8f9223119f971f1e9d41c99bb77a6968157daf8"),
    "gamegear.datgz" to BundledDatIntegrity(927, "8f65e28112275f8a92b7e959f3eb90eaa418ce411281e6f048561be4884c44b9"),
    "gb.datgz" to BundledDatIntegrity(2_284, "fbcde1841670c6951ede873e71e1377e5998e645815ef64ada11a8a085c5a157"),
    "gba.datgz" to BundledDatIntegrity(3_748, "6f9c39cb46586aa0490bef78013f6d0799709356d28b88fdabe68bb5d313d24d"),
    "gbc.datgz" to BundledDatIntegrity(2_612, "e0762cdc6b4230d44bbbb1a114b912c333564de17cbf44b1739b51efc4eb7d92"),
    "genesis.datgz" to BundledDatIntegrity(3_490, "8d83a19ad58a3ec9dc3cd0ba4d08bbdc057dd432173002737eb2e526e75a0595"),
    "mastersystem.datgz" to BundledDatIntegrity(1_216, "3a8837cb91bcaa7aafa9016fa092acd0893ca4fe1712ae0647d4d3288d9c752a"),
    "n64.datgz" to BundledDatIntegrity(1_261, "9c0d2a3ac5fd0f27d13baa71ea5c1b5c6fb01d04e1a7a0847bd72ab05e52b3b6"),
    "nds.datgz" to BundledDatIntegrity(7_690, "cab6fa5d3b6a4c65e3005f99ad389dd23cc65e8886d1ab1b447200eadc6f11ad"),
    "nds_dec.datgz" to BundledDatIntegrity(7_702, "7f2a78f3d638a0c703894f68e2dc32eed4b14a92739cc32123293d3ab787e3de"),
    "nes.datgz" to BundledDatIntegrity(7_292, "d91bc98390190dc4aacb3679dd1239bb63977881a0259d0f47e22802b6325d48"),
    "ngp.datgz" to BundledDatIntegrity(13, "4883e5104d82079473441d0ed41cce6bda23c8c38c5e68a214ac323b8ced7063"),
    "ngpc.datgz" to BundledDatIntegrity(128, "90d019eed646eff128c66b6b974c9c3b693b2fb0a569be70605067476a152d6b"),
    "pcengine.datgz" to BundledDatIntegrity(509, "2cd3bc6fc31bc34ee6816df0597de4b267e93ce6865b8545af7d63b6fa065fd4"),
    "pcenginecd.datgz" to BundledDatIntegrity(551, "a031a50852459f9586e1f4f983720b44980b336e583344ccc85b4b575c9698bc"),
    "pokemini.datgz" to BundledDatIntegrity(46, "f30b6d418a4d3c87e9376fc24ce03bd5098c27cdb197f5085bdb9bb76183a336"),
    "ps1.datgz" to BundledDatIntegrity(10_889, "4bac97c24fecae4a5ca0f315275ea3b449f676e14cb16d26e23ef3961b564a5c"),
    "ps2.datgz" to BundledDatIntegrity(11_735, "4ec61a3d298e7968c1001b40f522883c5d7a418281111f38997a80ae86f6fef9"),
    "ps3.datgz" to BundledDatIntegrity(4_515, "8d5513e7c5bb5de00f28c5d254f79199d8002df247d1ee18ac5e90a2605c4d52"),
    "psp.datgz" to BundledDatIntegrity(3_437, "140484798bda6fcbcb38a8ed62265f11a7ca6cf0beb63a8e1ec6b0d6799e9332"),
    "saturn.datgz" to BundledDatIntegrity(2_393, "c799b618779d4beaeec8fbf0a17eeb210fa461aad6137bb7a463d0307bf822c4"),
    "segacd.datgz" to BundledDatIntegrity(545, "48b5d3954259571cca34b8ecf6375c893b7cd2dfe407ab6e143f55b6dcc625b9"),
    "sg1000.datgz" to BundledDatIntegrity(247, "07c40fc4e779cc0e6859417ddf1fb0e89e8895e50bf9ed59602c208300727418"),
    "snes.datgz" to BundledDatIntegrity(4_318, "a0096cf948bdaf41325e7bd99fe672595a36fe8c1cb5d42a7bffc95165c70775"),
    "snk_neo_geo_cd.datgz" to BundledDatIntegrity(111, "df0e455748aef10b40c4ba619aa5e161162e1e1177d3db5e03850a6888b95e3e"),
    "virtualboy.datgz" to BundledDatIntegrity(78, "d2536f67242575fb5186853158e389252f68cab15db595cb8275439b459de9fe"),
    "wii.datgz" to BundledDatIntegrity(3_779, "14d3a19dde640200b9362cd5187cf9706f663f288264fbf824e67f80b7e22bfc"),
    "wiiu.datgz" to BundledDatIntegrity(490, "5ecaeff978b11c170af39ae2a8d48ff13abd8974a61c765a56fbbe2c1a67e8a3"),
    "wonderswan.datgz" to BundledDatIntegrity(257, "d1de821637eb8d82af9709f6d40897725ecb1884b240a5b23253b724ca5983ea"),
    "wonderswancolor.datgz" to BundledDatIntegrity(253, "f3d9423cc71bfe7c97a823540968614ca0cb10812c161aed45f276e94bdd4cec"),
    "xbox360.datgz" to BundledDatIntegrity(3_700, "344d074e1198a18b6b72b12fd270180896704b2f5c63248041bd520e563cb7dd"),
)

private fun packed(
    assetName: String,
    packedSizeBytes: Long,
    semanticMapSha256: String,
    packedSha256: String,
    unique: Int? = null,
    ambiguous: Int = 0,
) = BundledPackedDatIntegrity(
    assetName = assetName,
    packedSizeBytes = packedSizeBytes,
    semanticMapSha256 = semanticMapSha256,
    packedSha256 = packedSha256,
    expectedUniqueSha1Count = unique,
    expectedAmbiguousSha1Count = ambiguous,
)

/** Physical/layout locks generated only after all source sets were frozen. */
private val bundledPackedDatIntegrityBySource = mapOf(
    "3ds.datgz" to packed("3ds.midx", 101_171, "35a4d8089355468189a0ed0fe1b69dc49c63adcf5fea1bb1be1c96f2a4c28b89", "973d6ed401daa07ff399aaeb0d90d784ced0e9d6b042358e760d32de3ac6718d"),
    "3ds_dec.datgz" to packed("3ds_dec.midx", 101_131, "26952cf72411df5f38ba53e065a889e2c1223420dd6f718d5d8c928bc7460ba7", "c386c8e85c6f82eec813849e35c27baec0ce31eed952c87fea9b5b32255d6c29"),
    "atari2600.datgz" to packed("atari2600.midx", 39_322, "ddf4fb5e420ff0ad47b2bb38794ce7c1fcd148ae5b87bc0e62e7042776850dde", "d6f19a3ffa3a867704afe923c594d439cd1ba8581b1b74e13110f22ca3e43b2d"),
    "atari7800.datgz" to packed("atari7800.midx", 5_377, "1775c50e73b44cbd46d7c52a600f05cca44c0fefb67945ed3aedd56e7af2dc21", "a5f7fc2a9724f07cc365ff1a08764edf7191161081c2df88b4531d7e7c21779f"),
    "atarilynx.datgz" to packed("atarilynx.midx", 13_174, "63ac2c5f3fc57530aa46d90f69772180c8fd45ec79b1c35c9eb7274f41d6c9c2", "a9b5b01865ea49e773d935d6eb194f7e6ca67fb84b8a316ea380571739f65544"),
    "dreamcast.datgz" to packed("dreamcast.midx", 68_710, "42019747a09f568fb69ffc4118496c3f173e14e47dabf25906df8e22cfb09a13", "7b2b32c70abbb3e81d103bf23755e7083443e93b0f3ad7ddb6c713319ad36a1d"),
    "dreamcast_redump.datgz" to packed("dreamcast_redump.midx", 233_347, "3b06912bbffb32ba5ee58201f6cd90f71662326b5632d3d6b3a942b8714b0f48", "a8224b4da622332e578438203e9e8eaea0cb72d02c52078c2838209978bca75a", unique = 8_358, ambiguous = 1_536),
    "gamecube.datgz" to packed("gamecube.midx", 90_355, "a05f600e9b9924c62eeb5eb00a86e7d165fdea519a1e17731c682d64d005074c", "86c2d8138f331fa1727a04f8b9dd6e81653a6f5f9500e803be9a6d1465309cf2"),
    "gamegear.datgz" to packed("gamegear.midx", 41_325, "e5dd7e07be5b872fdf4fe411ecd94a75d6a95f1cd51df52cd61ea23d6bf5d41f", "62b38353eba1fa01626528c392ea2b20fb1beec18c8a0cb612317f9044c4e437"),
    "gb.datgz" to packed("gb.midx", 104_953, "f85cb1b651171a16aaf87bcc217c3f53ac06a3e342c2c20d4af8cf0a8492dece", "3e9bc6b9f2a3b84d21e3608c753f7e2aded5550e6c0ebff376908988740e5cc1"),
    "gba.datgz" to packed("gba.midx", 173_174, "d71af4c3d85a0a7bdb3669eca1bcdebb2a6b860764304365d495073222fdcf60", "3d29573ff464a01c2f7ff87f686d62663dc8ec2b69e6af73d598a63c3b2a5797"),
    "gbc.datgz" to packed("gbc.midx", 123_211, "8503dac32f58594428c961d747a72502eeecc8d79493dd14b205a798775ce7df", "bfa302469342c7a03e4a7470f35a02504de14bb48a722eeba6c0742514e99b70"),
    "genesis.datgz" to packed("genesis.midx", 155_196, "4430a1af2b261dad1b1a7e2ca8d902683f42d75a3bb74460206f9eab1e11316d", "334619829305e228e96217aa629a82d4bb78e055e5f723fc770bcb45407a0364"),
    "mastersystem.datgz" to packed("mastersystem.midx", 54_532, "71fa9c857e3bea850ba6ef9fcdb5e665b1936718740d363253f187614f929b56", "30be28fa7ded33272fa63daa658816bbdf0df9632a0322206910c5ae11600bfb"),
    "n64.datgz" to packed("n64.midx", 56_285, "e872b396621bdad4ff282696f4da7923d5cb500b92607461a41290614e8b78d6", "7cf3723f51d0286745b279758a1e40c989bc2c5f15cdfa834fc96a351e9ceff0"),
    "nds.datgz" to packed("nds.midx", 363_641, "c5ac460deef78eec753e22959467c8d05a882c283a906d4cca18130cc0616de4", "8dc06f853abc56de9b7c35aa4e11348038b26f98875e3bb33db53dd87276bd1c"),
    "nds_dec.datgz" to packed("nds_dec.midx", 364_220, "a2aa41dedb571179df30e404570f1e3dfb03be3fed039360c8ba86d1d6f68db1", "812c259cef1b5d530f884f67e29a0c7874ae382ca65d9363c37824ef27e61910"),
    "nes.datgz" to packed("nes.midx", 328_240, "e487d29631a8b075e4c8e8dcef440811d798981c085c517a3776a5f396e00248", "3b4039a41dd6c393e86ddc5310ba20b924caed004e764b76bebb12e3ecc4104c"),
    "ngp.datgz" to packed("ngp.midx", 984, "0a9f6d12d2899cef57185b457bf40d5cd34142295501f79781bb3e267ae3c60e", "837795a9405718061466035512f45bb8bc36b8bc49db3962f697564e785b9f0f"),
    "ngpc.datgz" to packed("ngpc.midx", 6_437, "687cfb30ad792b028705347391458158dbad37309ef8947e173a70a0ea152bc2", "6b7529aea8e718cf102bdaba5979ad93ff43c39cc30aeb3ff465f3414da583f9"),
    "pcengine.datgz" to packed("pcengine.midx", 23_499, "3c29fa2128d15565e1adee41e47ea9152fa2899149f0d0441ad62ecc04b73af4", "2faad90ec7a734d89c8e1a687f3e256d8eca9e235f36c26a2eb6c4aef75f1f3c"),
    "pcenginecd.datgz" to packed("pcenginecd.midx", 26_470, "53accf9df4c634c4642a386be4312a640c6aa80278c6a681b96daf9640a3b005", "2eab192282c6bafe6cf72e379648b623de43e4f0cdb550ea9b4c3308a08848a0"),
    "pokemini.datgz" to packed("pokemini.midx", 2_445, "914e390efa36dd79cfeb4e55e7d70a57b8a6135acdf34bc3da947de767465dcc", "b96447c9fd4fe8d4ddcdfc8f64801825796f6703c4d1742373a8fe3bbc41ee15"),
    "ps1.datgz" to packed("ps1.midx", 486_836, "b85c90e8a54469a4ff3656bc23817ffc68bdccc459e2505e12931231c797d3fe", "e5fa857c6d9ca34ed273e089595cbc85aa9ba64161e7d90921b5bca93a3705e1"),
    "ps2.datgz" to packed("ps2.midx", 528_188, "5bab90bc7b8f02e8c56846652301fa88c9ac9d55baa0067646caeca038acd175", "23eea9d459007e7960dcc151a8883d296c75b7173bb8489312b62fe4c9d92923"),
    "ps3.datgz" to packed("ps3.midx", 200_330, "765899bb064e8bb5a50fd7d79ec3c3fc17e35715fbf04f273174bd25931c1e2e", "492974d190ef13088462af0b82c7ce9a228bd7e759c5473c95d8c95ab6e3511e"),
    "psp.datgz" to packed("psp.midx", 158_522, "a8aa6a6f893bef3a4f7a8729d7442a274743012ac187f445b4ff8b9c5273c0d9", "53facdafe63fd0789d7c734485103fd77f904957bae1237839164f86252e329b"),
    "saturn.datgz" to packed("saturn.midx", 108_889, "665d9756c39210cd59e044b4514a38a14cd640bb9a70c43367f511584bbd9c04", "6de0bee1394247a24a8cbc88ddfbc3aa4cc8bd5f60b3ea06d7c16149438013dc"),
    "segacd.datgz" to packed("segacd.midx", 24_748, "6866dadf501ae561e1b9f9c56bfc6e5691f485a85b243ec60074669e3bbeb500", "d1e0ab05e658c94f94a48da9501beb121783b21096ab8694bf2c39268c2f85d1"),
    "sg1000.datgz" to packed("sg1000.midx", 11_065, "4cdf71339df83707a6c840ba0ccba381162436f6695557a4c952908df983c9e7", "4284c722a968aa02841096debfca8806b743ffd2fee29f557dc3096981c489bc"),
    "snes.datgz" to packed("snes.midx", 191_859, "86463bcf54d486a6addd56fd4977b9eaec87e6292ca79350e869409924e8132a", "d9f17ff67ef718f739ec7b4a9ccd644f5358f9d2e8ac74b58d358621ad2a508f"),
    "snk_neo_geo_cd.datgz" to packed("snk_neo_geo_cd.midx", 5_993, "45a6f0966db094fbf434c17b801a27c2c196892a58eca7ba08b1aa496c6bf6de", "2f6dbf6adb5a0929963e9f085e2e4b6bcd86fa5ac3d6a27436b0b3cc28d1a51e"),
    "virtualboy.datgz" to packed("virtualboy.midx", 3_930, "a307ba5bb97abe05ff4734f43d0b631714068c70ce587a337cdfaf76cde02de7", "6e8c68657c8be470c9fd6c579dfbda3de2128cea00521a3b93034e5b8140e80f"),
    "wii.datgz" to packed("wii.midx", 170_697, "67ad92cb178136975d859a2824ded6f63b610af1ee296afc87e7862aa073d5ab", "6fc5366ee0f9eb941c27b2f6d8e1db707b52799b1c43675eaf39df260d388b31"),
    "wiiu.datgz" to packed("wiiu.midx", 21_842, "795d9169e42721b9e7500b7da5f89742793b773b1811ae97c47ded3049679c14", "521b11141c852c4fd1146ace686c1765194a85e21f50ba335fc21f2e68061f82"),
    "wonderswan.datgz" to packed("wonderswan.midx", 12_679, "d5d4aaacd26c50a64f8fd194f530aac68e1ac972285a20a4f39afa7d794be10a", "7a5ae9b21a45a118189d8f1281390b83c20ea8b92ffae4de04a66f2c30268609"),
    "wonderswancolor.datgz" to packed("wonderswancolor.midx", 12_515, "d5434482d203f1ee1bff03c0030cf86c4d92fb9278f265313e13fc13e8e62c22", "19d9708f7c454c97d7b72cb62d894ef89c6ccec75ada9631474c841ac37265f1"),
    "xbox360.datgz" to packed("xbox360.midx", 164_557, "03e9f9e90611fc3157a9609a6ca25123f1d0755cc6af8730986ff641731cd73f", "66aba581c36c8931832728ad04574b6e48dfa102fc7612e3c30cdfc8b0ca69b3"),
)

private fun bundledDatIntegrity(assetName: String): BundledDatIntegrity =
    requireNotNull(bundledDatIntegrityByAsset[assetName]) { "missing semantic integrity lock for $assetName" }

private fun bundledPackedDatIntegrity(sourceId: String): BundledPackedDatIntegrity =
    requireNotNull(bundledPackedDatIntegrityBySource[sourceId]) { "missing packed integrity lock for $sourceId" }

/**
 * The curated DAT sources shipped as immutable `app/src/main/assets/dat/<slug>.midx` indexes. Provisioning
 * reads No-Intro/Redump/GameTDB Logiqx XML, requires SHA-1 on every usable row, preserves each source as a
 * separate ambiguity domain, and stamps the source header/count/input digest into its packed shard. XML and
 * CRC tables do not ship. The platform key is the resolver classifier id — DS is "ds", Genesis/Mega Drive
 * is "megadrive", and 3DS is "3ds".
 *
 * Not bundled (honestly deferred): **switch** (No-Intro does not catalog it — Switch uses title-IDs, not
 * hash DATs) and **arcade** (full MAME `-listxml` is 200 MB+, not sanely bundle-able). These simply have no
 * entry here, so [BundledDatSource] returns null for them — never a wrong guess.
 *
 * Provisioning is reproducible via `tools/fetch-dats.sh` and `tools/pack-dats.py`. These remain metadata
 * checksums + names — never content/BIOS/firmware.
 */
object BundledDatManifest {
    val assets: List<BundledDatAsset> = listOf(
        // ── Cartridge — No-Intro (parent-clone) ──────────────────────────────
        // NES is the HEADERLESS set — paired with RomFingerprinter stripping the 16-byte iNES header.
        BundledDatAsset(
            PlatformId.NES,
            "nes.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Nintendo Entertainment System (Headerless) (Parent-Clone)",
            595_188,
        ),
        // SNES: No-Intro publishes only one cartridge set (no Headered/Headerless split); it is the
        // headerless convention (all <rom> entries are .sfc, sizes carry no +512 copier-header offset).
        // RomFingerprinter has no SNES-specific header strip, so it hashes files as-is — matches directly.
        BundledDatAsset(
            PlatformId.SNES,
            "snes.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Super Nintendo Entertainment System (Parent-Clone)",
            353_233,
        ),
        BundledDatAsset(
            PlatformId.GB,
            "gb.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Game Boy (Parent-Clone)",
            193_654,
        ),
        BundledDatAsset(
            PlatformId.GBC,
            "gbc.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Game Boy Color (Parent-Clone)",
            224_056,
        ),
        BundledDatAsset(
            PlatformId.GBA,
            "gba.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Game Boy Advance (Parent-Clone)",
            320_859,
        ),
        // DS/3DS carry BOTH the Encrypted (raw cart dump) and Decrypted variants — a platform may bundle
        // several DATs and [BundledDatSource] tries each, since a user's dump matches exactly one.
        BundledDatAsset(
            PlatformId.DS,
            "nds.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Nintendo DS (Encrypted) (Parent-Clone)",
            672_875,
        ),
        BundledDatAsset(
            PlatformId.DS,
            "nds_dec.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Nintendo DS (Decrypted) (Parent-Clone)",
            673_429,
        ),
        BundledDatAsset(
            PlatformId.THREE_DS,
            "3ds.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Nintendo 3DS (Encrypted) (Parent-Clone)",
            186_661,
        ),
        BundledDatAsset(
            PlatformId.THREE_DS,
            "3ds_dec.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Nintendo 3DS (Decrypted) (Parent-Clone)",
            186_718,
        ),
        BundledDatAsset(
            PlatformId.N64,
            "n64.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Nintendo 64 (BigEndian) (Parent-Clone)",
            103_685,
        ),
        BundledDatAsset(
            PlatformId("megadrive"),
            "genesis.datgz",
            MetadataOrigin.NO_INTRO,
            "Sega - Mega Drive - Genesis (Parent-Clone)",
            286_188,
        ),
        BundledDatAsset(
            PlatformId("mastersystem"),
            "mastersystem.datgz",
            MetadataOrigin.NO_INTRO,
            "Sega - Master System - Mark III (Parent-Clone)",
            99_902,
        ),
        BundledDatAsset(
            PlatformId("gamegear"),
            "gamegear.datgz",
            MetadataOrigin.NO_INTRO,
            "Sega - Game Gear (Parent-Clone)",
            75_510,
        ),
        BundledDatAsset(
            PlatformId("sg1000"),
            "sg1000.datgz",
            MetadataOrigin.NO_INTRO,
            "Sega - SG-1000 - SC-3000 (Parent-Clone)",
            20_660,
        ),
        BundledDatAsset(
            PlatformId("atari2600"),
            "atari2600.datgz",
            MetadataOrigin.NO_INTRO,
            "Atari - Atari 2600 (Parent-Clone)",
            70_304,
        ),
        // The A78 and LNX sets retain the headers that the resolver's `.a78`/`.lnx` contract hashes.
        BundledDatAsset(
            PlatformId("atari7800"),
            "atari7800.datgz",
            MetadataOrigin.NO_INTRO,
            "Atari - Atari 7800 (A78) (Parent-Clone)",
            9_571,
        ),
        BundledDatAsset(
            PlatformId("atarilynx"),
            "atarilynx.datgz",
            MetadataOrigin.NO_INTRO,
            "Atari - Atari Lynx (LNX) (Parent-Clone)",
            23_123,
        ),
        BundledDatAsset(
            PlatformId("pcengine"),
            "pcengine.datgz",
            MetadataOrigin.NO_INTRO,
            "NEC - PC Engine - TurboGrafx-16 (Parent-Clone)",
            42_707,
        ),
        BundledDatAsset(
            PlatformId("ngp"),
            "ngp.datgz",
            MetadataOrigin.NO_INTRO,
            "SNK - NeoGeo Pocket (Parent-Clone)",
            1_743,
        ),
        BundledDatAsset(
            PlatformId("ngpc"),
            "ngpc.datgz",
            MetadataOrigin.NO_INTRO,
            "SNK - NeoGeo Pocket Color (Parent-Clone)",
            12_086,
        ),
        BundledDatAsset(
            PlatformId("virtualboy"),
            "virtualboy.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Virtual Boy (Parent-Clone)",
            6_939,
        ),
        BundledDatAsset(
            PlatformId("wonderswan"),
            "wonderswan.datgz",
            MetadataOrigin.NO_INTRO,
            "Bandai - WonderSwan (Parent-Clone)",
            22_034,
        ),
        BundledDatAsset(
            PlatformId("wonderswancolor"),
            "wonderswancolor.datgz",
            MetadataOrigin.NO_INTRO,
            "Bandai - WonderSwan Color (Parent-Clone)",
            21_586,
        ),
        BundledDatAsset(
            PlatformId("pokemini"),
            "pokemini.datgz",
            MetadataOrigin.NO_INTRO,
            "Nintendo - Pokemon Mini (Parent-Clone)",
            4_348,
        ),
        // ── Disc / .chd — MAMERedump (Redump data, logiqx XML) ───────────────
        BundledDatAsset(PlatformId.PS1, "ps1.datgz", MetadataOrigin.REDUMP, "Sony - PlayStation", 526_165),
        BundledDatAsset(
            PlatformId.PSP,
            "psp.datgz",
            MetadataOrigin.REDUMP,
            "Sony - PlayStation Portable",
            167_247,
        ),
        BundledDatAsset(
            PlatformId.DREAMCAST,
            "dreamcast.datgz",
            MetadataOrigin.REDUMP,
            "Sega - Dreamcast",
            73_450,
        ), // MAMERedump <disk> (.chd)
        BundledDatAsset(
            PlatformId.DREAMCAST,
            "dreamcast_redump.datgz",
            MetadataOrigin.REDUMP,
            "Sega - Dreamcast",
            746_815,
        ), // Redump per-track (.gdi/.cue)
        BundledDatAsset(
            PlatformId.GAMECUBE,
            "gamecube.datgz",
            MetadataOrigin.REDUMP,
            "Nintendo - GameCube",
            121_720,
        ),
        BundledDatAsset(PlatformId.WII, "wii.datgz", MetadataOrigin.REDUMP, "Nintendo - Wii", 232_084),
        BundledDatAsset(PlatformId("ps2"), "ps2.datgz", MetadataOrigin.REDUMP, "Sony - PlayStation 2", 550_892),
        BundledDatAsset(PlatformId("saturn"), "saturn.datgz", MetadataOrigin.REDUMP, "Sega - Saturn", 118_911),
        BundledDatAsset(
            PlatformId("segacd"),
            "segacd.datgz",
            MetadataOrigin.REDUMP,
            "Sega - Mega CD & Sega CD",
            27_285,
        ),
        BundledDatAsset(
            PlatformId("pcenginecd"),
            "pcenginecd.datgz",
            MetadataOrigin.REDUMP,
            "NEC - PC Engine CD & TurboGrafx CD",
            28_755,
        ),
        BundledDatAsset(
            PlatformId("snk_neo_geo_cd"),
            "snk_neo_geo_cd.datgz",
            MetadataOrigin.REDUMP,
            "SNK - Neo Geo CD",
            6_601,
        ),
        // 3DO is deliberately absent while its registry profile is GAP/contentContract=null; original
        // Xbox is absent because MAMERedump publishes no CHD set and Redump whole-image hashes cannot
        // identify local CHDs. Shipping either blob now would create an inert catalogue.
        // ── Legacy HD generation — Redump <rom> (whole-image sha1) ────────────
        // Detection includes the legacy HD generation. Both are Redump
        // DISC sets, verified by reading each shipped blob's own <header><name> rather than its
        // filename: "Sony - PlayStation 3" (4,503 games / 4,515 <rom>) and "Microsoft - Xbox 360"
        // (3,691 games / 3,700 <rom>). Cheap despite being the newest consoles here — a PS3/360 title
        // is ONE ISO (1.00 roms/game), so ps3.datgz is 0.39 MiB and xbox360.datgz 0.29 MiB; the
        // multi-track inflation that makes PS1/Saturn expensive is a CD phenomenon and doesn't apply.
        //
        // TWO honest limits, both structural, neither a defect in this wiring:
        //  1. These are <rom> sets, so identity is the WHOLE-IMAGE sha1. A .chd (or any other
        //     re-container) can never match by construction — only an untouched .iso can. There is no
        //     <disk> equivalent published for either platform.
        //  2. Matching one costs a full read of a ~7-8 GB image (the sampled PS3 entry is 8,372,944,896
        //     B; the 360 one 7,834,892,288 B). Against the RA lane's 64 MiB refusal ceiling that is
        //     ~125x; against the largest file actually measured on the sampled card (a 33,554,432 B zip
        //     entry) it is ~250x. Either way these want an explicit user action rather than the
        //     background identify pass.
        // Bundling them now is still right: the catalogues are tiny, and having the DAT present is what
        // makes a future opt-in match possible at all. Zero such files exist on the sampled card at measurement time.
        BundledDatAsset(PlatformId("ps3"), "ps3.datgz", MetadataOrigin.REDUMP, "Sony - PlayStation 3", 410_984),
        BundledDatAsset(
            PlatformId("xbox360"),
            "xbox360.datgz",
            MetadataOrigin.REDUMP,
            "Microsoft - Xbox 360",
            307_493,
        ),
        // GameTDB's hash-bearing Wii U rows cover canonical full WUD images. WUX/WUA/RPX cannot share
        // those whole-image digests by construction and remain honest misses until a local title-id
        // extractor for their containers is separately proven. Permission is GameTDB's public developer
        // invitation, conditioned on the visible https://www.gametdb.com/ credit shipped with this asset.
        BundledDatAsset(
            PlatformId("nintendo_wii_u"),
            "wiiu.datgz",
            MetadataOrigin.TITLE_CATALOG,
            "GameTDB - Nintendo Wii U",
            35_042,
        ),
    )

    /**
     * Version-independent semantic latch for every bundled catalogue. Logical source, routing/variant,
     * parsed input or lookup-visible semantics move it; `.midx` layout/compression/physical bytes do not.
     */
    val catalogueStamp: String = catalogueStamp(assets, BundledIdManifest.assets)

    private val byPlatform: Map<String, List<BundledDatAsset>> = assets.groupBy { it.platformId.value }

    /** All DATs bundled for [platformId] (usually one; DS/3DS carry an Encrypted + a Decrypted variant). */
    fun forPlatform(platformId: PlatformId): List<BundledDatAsset> = byPlatform[platformId.value].orEmpty()
}

internal fun catalogueStamp(
    assets: List<BundledDatAsset>,
    idAssets: List<BundledIdAsset> = emptyList(),
): String {
    val datLines = assets.map {
        listOf(
            "DAT",
            it.sourceId,
            it.platformId.value,
            it.origin.name,
            it.expectedHeaderName,
            it.expectedEntryCount.toString(),
            it.contentSha256,
            it.semanticMapSha256,
        ).joinToString("\t")
    }
    val idLines = idAssets.map {
        listOf(
            "ID",
            it.platformId.value,
            it.assetName,
            it.origin.name,
            it.keyType.name,
            it.expectedEntryCount.toString(),
            it.contentSha256,
        ).joinToString("\t")
    }
    val material = (datLines + idLines)
        .sorted()
        .joinToString(separator = "\n", prefix = "mimir-bundled-catalogue-v4\n")
        .toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256")
        .digest(material)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
