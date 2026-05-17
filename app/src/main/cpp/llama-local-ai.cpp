#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LocalAiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::vector<std::string> g_logs;

static void add_log(const char* level, const char* msg) {
    g_logs.push_back(std::string("[") + level + "] " + msg);
    while (g_logs.size() > 500) g_logs.erase(g_logs.begin());
}

static void llama_log_callback(ggml_log_level level, const char* text, void*) {
    std::string msg(text);
    while (!msg.empty() && (msg.back()=='\n'||msg.back()=='\r')) msg.pop_back();
    if (msg.empty()) return;
    const char* lvl = (level==GGML_LOG_LEVEL_ERROR)?"ERR":(level==GGML_LOG_LEVEL_WARN)?"WARN":"INFO";
    if (level != GGML_LOG_LEVEL_DEBUG) add_log(lvl, msg.c_str());
}

struct ModelHandles {
    llama_model* model;
    llama_context* ctx;
    int32_t eos_token;
};

/** Sanitize to valid Modified UTF-8 — NEVER crashes NewStringUTF */
static std::string safe_utf8(const std::string& in) {
    std::string out; out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        unsigned char c0 = (unsigned char)in[i];
        if (c0 <= 0x7F) { out += (char)c0; i++; }
        else if (c0 >= 0xC2 && c0 <= 0xDF) {
            if (i+1 < in.size() && ((unsigned char)in[i+1] & 0xC0) == 0x80) { out += in[i]; out += in[i+1]; i += 2; }
            else { i++; }
        } else if (c0 >= 0xE0 && c0 <= 0xEF) {
            if (i+2 < in.size() && ((unsigned char)in[i+1] & 0xC0) == 0x80 && ((unsigned char)in[i+2] & 0xC0) == 0x80) {
                if (c0 == 0xE0 && (unsigned char)in[i+1] < 0xA0) { i++; continue; }
                if (c0 == 0xED && (unsigned char)in[i+1] > 0x9F) { i++; continue; }
                out += in[i]; out += in[i+1]; out += in[i+2]; i += 3;
            } else { i++; }
        } else if (c0 >= 0xF0 && c0 <= 0xF4) {
            if (i+3 < in.size() && ((unsigned char)in[i+1]&0xC0)==0x80 && ((unsigned char)in[i+2]&0xC0)==0x80 && ((unsigned char)in[i+3]&0xC0)==0x80) {
                if (c0==0xF0 && (unsigned char)in[i+1]<0x90) { i++; continue; }
                if (c0==0xF4 && (unsigned char)in[i+1]>0x8F) { i++; continue; }
                out += in[i]; out += in[i+1]; out += in[i+2]; out += in[i+3]; i += 4;
            } else { i++; }
        } else { i++; }
    }
    return out;
}

static jstring safe_jstring(JNIEnv* env, const std::string& in) {
    return env->NewStringUTF(safe_utf8(in).c_str());
}

static std::string token_piece(const llama_context* ctx, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(llama_model_get_vocab(llama_get_model(ctx)), tok, buf, sizeof(buf), 0, true);
    if (n <= 0) return "";
    if (n > (int)sizeof(buf)) {
        std::string big(n, '\0');
        int n2 = llama_token_to_piece(llama_model_get_vocab(llama_get_model(ctx)), tok, big.data(), n, 0, true);
        if (n2 > 0) { big.resize(n2); return big; }
        return "";
    }
    return std::string(buf, n);
}

extern "C" {

JNIEXPORT void JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_initLogs(JNIEnv*, jobject) {
    g_logs.clear();
    llama_log_set(llama_log_callback, nullptr);
    add_log("INFO", "LocalAI engine initialized");
}

JNIEXPORT jstring JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_getLogs(JNIEnv* env, jobject) {
    std::string s;
    for (auto& l : g_logs) s += l + "\n";
    return safe_jstring(env, s);
}

JNIEXPORT jlong JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_loadModel(JNIEnv* env, jobject,
        jstring path, jint n_ctx, jint n_threads) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    add_log("INFO", (std::string("Loading: ") + p).c_str());
    FILE* f = fopen(p, "rb");
    if (!f) { add_log("ERR", "Cannot open file"); env->ReleaseStringUTFChars(path, p); return 0; }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fclose(f);
    add_log("INFO", (std::string("Size: ") + std::to_string(sz/(1024*1024)) + " MB").c_str());
    env->ReleaseStringUTFChars(path, p);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only

    auto t0 = std::chrono::steady_clock::now();
    llama_model* model = llama_model_load_from_file(p, mp);
    long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();

    if (!model) { add_log("ERR", "Model load FAILED"); return 0; }
    add_log("INFO", (std::string("Loaded in ") + std::to_string(ms) + " ms").c_str());

    const llama_vocab* v = llama_model_get_vocab(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx; cp.n_batch = n_ctx;
    cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    cp.no_perf = true;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); add_log("ERR", "Context init FAILED"); return 0; }

    add_log("INFO", "Model ready");
    return reinterpret_cast<jlong>(new ModelHandles{model, ctx, (int32_t)llama_vocab_eos(v)});
}

JNIEXPORT void JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_freeModel(JNIEnv*, jobject, jlong h) {
    auto* s = reinterpret_cast<ModelHandles*>(h);
    if (s) {
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        delete s;
        llama_backend_free();
        add_log("INFO", "Model freed");
    }
}

/**
 * Unified generate: mode 0=EN→CN, 1=CN→EN, 2=Chat
 * Returns: "result text\n\n---\nstats"
 */
JNIEXPORT jstring JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_generate(
        JNIEnv* env, jobject, jlong h, jstring jtext, jint mode, jint max_tokens) {
    auto* s = reinterpret_cast<ModelHandles*>(h);
    if (!s || !s->ctx) return env->NewStringUTF("[ERR] model not loaded");

    const char* ct = env->GetStringUTFChars(jtext, nullptr);
    std::string text(ct);
    env->ReleaseStringUTFChars(jtext, ct);

    // System prompt per mode
    std::string prompt;
    float temp, penalty;
    switch (mode) {
        case 0: // EN→CN
            prompt = "Translate to Chinese. Only output the translation, nothing else:\n\n" + text + "\n\nChinese:";
            temp = 0.3f; penalty = 1.2f; break;
        case 1: // CN→EN
            prompt = "Translate to English. Only output the translation, nothing else:\n\n" + text + "\n\nEnglish:";
            temp = 0.3f; penalty = 1.2f; break;
        case 2: // Chat
            prompt = "你是一个简洁的AI助手。用简短准确的语言回答以下问题。不要重复。直接回答。\n\n" + text;
            temp = 0.6f; penalty = 1.3f; break;
        default:
            prompt = text; temp = 0.5f; penalty = 1.1f; break;
    }

    add_log("INFO", (std::string("Mode=") + std::to_string(mode) +
                      " prompt=" + std::to_string(prompt.size()) + "B").c_str());

    llama_kv_cache_clear(s->ctx);

    // Tokenize
    const llama_vocab* v = llama_model_get_vocab(s->model);
    int n_tok = -llama_tokenize(v, prompt.c_str(), prompt.size(), NULL, 0, true, true);
    if (n_tok <= 0) return env->NewStringUTF("[ERR] tokenize");
    std::vector<llama_token> toks(n_tok);
    llama_tokenize(v, prompt.c_str(), prompt.size(), toks.data(), n_tok, true, true);

    // Sampler chain
    auto sp = llama_sampler_chain_default_params(); sp.no_perf = true;
    llama_sampler* sm = llama_sampler_chain_init(sp);
    if (temp > 0) llama_sampler_chain_add(sm, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sm, llama_sampler_init_penalties(32, penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(sm, llama_sampler_init_greedy());
    if (temp > 0) llama_sampler_chain_add(sm, llama_sampler_init_dist(42));

    std::string result;
    llama_batch batch = llama_batch_get_one(toks.data(), toks.size());

    auto t0 = std::chrono::steady_clock::now();
    long first_ms = 0; int gen = 0; bool repeat_stop = false;

    std::vector<llama_token> recent_toks;
    std::vector<std::string> recent_phrases;

    for (int i = 0; i < max_tokens; i++) {
        auto s0 = std::chrono::steady_clock::now();
        int ret = llama_decode(s->ctx, batch);
        long dms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - s0).count();
        if (i == 0) { first_ms = dms; add_log("INFO", (std::string("First: ") + std::to_string(dms) + "ms").c_str()); }
        if (ret != 0) { add_log("ERR", (std::string("decode: ") + std::to_string(ret)).c_str()); break; }
        gen++;

        llama_token id = llama_sampler_sample(sm, s->ctx, -1);
        if ((int32_t)id == s->eos_token) { add_log("INFO", "EOS"); break; }

        // Token-level repetition detection
        recent_toks.push_back(id);
        bool tok_repeat = false;
        if (recent_toks.size() >= 4) {
            tok_repeat = true;
            for (size_t j = recent_toks.size()-4; j < recent_toks.size()-1; j++) {
                if (recent_toks[j] != recent_toks.back()) { tok_repeat = false; break; }
            }
        }
        while (recent_toks.size() > 64) recent_toks.erase(recent_toks.begin());
        if (tok_repeat) { add_log("WARN", "Token repeat, stopping"); repeat_stop = true; break; }

        std::string piece = token_piece(s->ctx, id);
        if (!piece.empty()) {
            result += piece;
            // Phrase-level repetition detection (on sentence boundaries)
            if (piece == "。" || piece == "." || piece == "\n") {
                auto pos = result.rfind('\n'); if (pos == std::string::npos) pos = 0;
                std::string last_sent = result.substr(pos);
                recent_phrases.push_back(last_sent);
                if (recent_phrases.size() >= 3) {
                    if (recent_phrases[recent_phrases.size()-1] == recent_phrases[recent_phrases.size()-2] &&
                        recent_phrases[recent_phrases.size()-2] == recent_phrases[recent_phrases.size()-3]) {
                        add_log("WARN", "Phrase repeat, stopping"); repeat_stop = true; break;
                    }
                    while (recent_phrases.size() > 6) recent_phrases.erase(recent_phrases.begin());
                }
            }
        }
        batch = llama_batch_get_one(&id, 1);
    }

    long total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    llama_kv_cache_clear(s->ctx);
    llama_sampler_free(sm);

    float spd = total_ms > 0 ? gen * 1000.0f / total_ms : 0;
    std::string tag = repeat_stop ? "[防循环截断] " : "";
    std::string info = tag + std::to_string(gen) + " tok, " + std::to_string(total_ms) +
        "ms, first=" + std::to_string(first_ms) + "ms, " +
        std::to_string((int)(spd*10)/10.0f) + " tok/s";
    add_log("INFO", info.c_str());

    std::string output = safe_utf8(result) + "\n\n---\n" + info;
    return safe_jstring(env, output);
}

} // extern "C"
