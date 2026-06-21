// 防重签名 native 校验层:对传入的签名证书 DER 计算 SHA-256,与 pinHex 比对。
// 把「哈希 + 比对」放在 native,比纯 dex 更难被随手 patch。配合 Integrity.kt 双层校验。
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>

namespace {

// ---- 自包含 SHA-256(public domain 风格,无外部依赖) ----
struct Sha256 {
    uint32_t s[8];
    uint64_t len = 0;
    uint8_t  buf[64];
    size_t   blen = 0;
    static uint32_t ror(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }
    void init() {
        static const uint32_t iv[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                                       0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
        memcpy(s, iv, sizeof iv); len = 0; blen = 0;
    }
    void block(const uint8_t* p) {
        static const uint32_t k[64] = {
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = (uint32_t(p[i*4])<<24)|(uint32_t(p[i*4+1])<<16)|(uint32_t(p[i*4+2])<<8)|uint32_t(p[i*4+3]);
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = ror(w[i-15],7)^ror(w[i-15],18)^(w[i-15]>>3);
            uint32_t s1 = ror(w[i-2],17)^ror(w[i-2],19)^(w[i-2]>>10);
            w[i] = w[i-16]+s0+w[i-7]+s1;
        }
        uint32_t a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1=ror(e,6)^ror(e,11)^ror(e,25), ch=(e&f)^((~e)&g);
            uint32_t t1=h+S1+ch+k[i]+w[i];
            uint32_t S0=ror(a,2)^ror(a,13)^ror(a,22), mj=(a&b)^(a&c)^(b&c);
            uint32_t t2=S0+mj;
            h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
        }
        s[0]+=a;s[1]+=b;s[2]+=c;s[3]+=d;s[4]+=e;s[5]+=f;s[6]+=g;s[7]+=h;
    }
    void update(const uint8_t* p, size_t n) {
        len += n;
        while (n) {
            size_t take = 64 - blen; if (take > n) take = n;
            memcpy(buf+blen, p, take); blen += take; p += take; n -= take;
            if (blen == 64) { block(buf); blen = 0; }
        }
    }
    void final(uint8_t out[32]) {
        uint64_t bits = len * 8;
        uint8_t pad = 0x80; update(&pad, 1);
        uint8_t z = 0; while (blen != 56) update(&z, 1);
        uint8_t L[8]; for (int i = 0; i < 8; i++) L[i] = uint8_t(bits >> (56 - i*8));
        update(L, 8);
        for (int i = 0; i < 8; i++) {
            out[i*4]=uint8_t(s[i]>>24); out[i*4+1]=uint8_t(s[i]>>16);
            out[i*4+2]=uint8_t(s[i]>>8); out[i*4+3]=uint8_t(s[i]);
        }
    }
};

std::string sha256Hex(const uint8_t* p, size_t n) {
    Sha256 c; c.init(); c.update(p, n);
    uint8_t d[32]; c.final(d);
    static const char* hx = "0123456789abcdef";
    std::string out; out.resize(64);
    for (int i = 0; i < 32; i++) { out[i*2]=hx[d[i]>>4]; out[i*2+1]=hx[d[i]&0xf]; }
    return out;
}

// 常量时间比较小写 hex 串
bool ctEq(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    int r = 0;
    for (size_t i = 0; i < a.size(); i++) r |= (a[i] ^ b[i]);
    return r == 0;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ktwo_cts_license_Integrity_nativeVerify(JNIEnv* env, jobject /*thiz*/,
                                                 jbyteArray certDer, jstring pinHex) {
    if (pinHex == nullptr) return JNI_FALSE;
    const char* pinC = env->GetStringUTFChars(pinHex, nullptr);
    std::string pin = pinC ? pinC : "";
    if (pinC) env->ReleaseStringUTFChars(pinHex, pinC);
    // 转小写
    for (auto& ch : pin) if (ch >= 'A' && ch <= 'F') ch = char(ch - 'A' + 'a');
    if (pin.empty()) return JNI_TRUE;  // 未配置指纹 → 放行
    if (certDer == nullptr) return JNI_FALSE;

    jsize n = env->GetArrayLength(certDer);
    if (n <= 0) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(certDer, nullptr);
    if (!bytes) return JNI_FALSE;
    std::string actual = sha256Hex(reinterpret_cast<const uint8_t*>(bytes), (size_t)n);
    env->ReleaseByteArrayElements(certDer, bytes, JNI_ABORT);

    return ctEq(actual, pin) ? JNI_TRUE : JNI_FALSE;
}
