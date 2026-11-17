// ═══════════════════════════════════════════════════════════════════════════
// RTMPOSE-JNI — the native inference bridge for the RTMPose rep engine (vC58).
//
// Contract (gate-proven in rep-sim/motion: pipeline_validate.py GREEN,
// ncnn_parity_gate.py GREEN, fp16 payload bit-faithful to ONNX fp32):
//   in:  "in0"  192x256x3, PIXEL_BGR (channel0=B — the official OpenCV order),
//        mean {123.675,116.28,103.53}, norm {1/58.395,1/57.12,1/57.375}
//   out: "out0" simcc_x 17x384, "out1" simcc_y 17x512 (temperature-scaled
//        logits — NO softmax; score = maxX * maxY, position = argmax/2)
// fp16 pitfall (mmpose#2793): packed/arithmetic OFF; storage ON (halves RAM,
// verified lossless by the parity gate: maxPosDelta 0.00px, scoreDelta 0.002).
// ═══════════════════════════════════════════════════════════════════════════
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <ncnn/net.h>
#include <ncnn/mat.h>
#include <vector>

namespace {
constexpr int MW = 192;
constexpr int MH = 256;
constexpr int KPTS = 17;
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_thesystem_app_ui_training_estimate_RtmposeNative_init(
        JNIEnv* env, jclass /*clazz*/, jobject assetManager,
        jstring paramPath, jstring binPath, jint threads) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) return 0;

    const char* pp = env->GetStringUTFChars(paramPath, nullptr);
    const char* bp = env->GetStringUTFChars(binPath, nullptr);

    auto* net = new ncnn::Net();
    net->opt.use_vulkan_compute = false;   // CPU law: deterministic everywhere, no driver lottery
    net->opt.use_fp16_packed = false;      // mmpose#2793 — fp16 packing corrupts this graph
    net->opt.use_fp16_arithmetic = false;
    net->opt.use_fp16_storage = true;      // gate-verified: halves model RAM, decode-identical
    net->opt.num_threads = threads > 0 ? threads : 4;

    const int r1 = net->load_param(mgr, pp);
    const int r2 = net->load_model(mgr, bp);
    env->ReleaseStringUTFChars(paramPath, pp);
    env->ReleaseStringUTFChars(binPath, bp);

    if (r1 != 0 || r2 != 0) {
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_thesystem_app_ui_training_estimate_RtmposeNative_detect(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jobject bgrBuf) {
    ncnn::Net* net = reinterpret_cast<ncnn::Net*>(handle);
    if (net == nullptr) return nullptr;
    const auto* px = static_cast<const unsigned char*>(env->GetDirectBufferAddress(bgrBuf));
    if (px == nullptr) return nullptr;

    ncnn::Mat in = ncnn::Mat::from_pixels(px, ncnn::Mat::PIXEL_BGR, MW, MH);
    const float mean[3] = {123.675f, 116.28f, 103.53f};
    const float norm[3] = {1.f / 58.395f, 1.f / 57.12f, 1.f / 57.375f};
    in.substract_mean_normalize(mean, norm);

    ncnn::Extractor ex = net->create_extractor();
    ex.input("in0", in);
    ncnn::Mat out0, out1;
    if (ex.extract("out0", out0) != 0 || ex.extract("out1", out1) != 0) return nullptr;

    const ncnn::Mat& sx = out0.w == 384 ? out0 : out1;   // contract guard, not a guess
    const ncnn::Mat& sy = out0.w == 384 ? out1 : out0;
    if (sx.h != KPTS || sy.h != KPTS || sx.w != 384 || sy.w != 512) return nullptr;

    std::vector<float> kpts(KPTS * 3);
    for (int j = 0; j < KPTS; j++) {
        const float* rowX = sx.row(j);
        const float* rowY = sy.row(j);
        int ix = 0, iy = 0;
        for (int k = 1; k < sx.w; k++) if (rowX[k] > rowX[ix]) ix = k;
        for (int k = 1; k < sy.w; k++) if (rowY[k] > rowY[iy]) iy = k;
        kpts[j * 3 + 0] = ix / 2.0f;             // simcc split ratio 2.0
        kpts[j * 3 + 1] = iy / 2.0f;
        kpts[j * 3 + 2] = rowX[ix] * rowY[iy];   // peak product — NO softmax (the flat-score trap)
    }

    jfloatArray res = env->NewFloatArray(KPTS * 3);
    if (res == nullptr) return nullptr;
    env->SetFloatArrayRegion(res, 0, KPTS * 3, kpts.data());
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_thesystem_app_ui_training_estimate_RtmposeNative_release(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}
