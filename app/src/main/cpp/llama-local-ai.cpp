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

static void add_app_log(const char* level, const char* msg) {
    g_logs.push_back(std::string("[") + level + "] " + msg);
    while (g_logs.size() > 500) g_logs.erase(g_logs.begin());
}

static void llama_log_callback(ggml_log_level level, const char* text, void*) {
    std::string msg(text);
    while (!msg.empty() && (msg.back()=='\n'||msg.back()=='\r')) msg.pop_back();
    if (msg.empty()) return;
    const char* lvl = (level==GGML_LOG_LEVEL_ERROR)?"ERR":(level==GGML_LOG_LEVEL_WARN)?"WARN":"INFO";
    if (level != GGML_LOG_LEVEL_DEBUG) add_app_log(lvl, msg.c_str());
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
    add_app_log("INFO", "LocalAI engine initialized");
}

JNIEXPORT void JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_clearLogs(JNIEnv*, jobject) {
    g_logs.clear();
}

JNIEXPORT jstring JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_getLogs(JNIEnv* env, jobject) {
    std::string s;
    for (auto& l : g_logs) s += l + "\n";
    return safe_jstring(env, s);
}

JNIEXPORT jlong JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_loadModel(JNIEnv* env, jobject,
        jstring path, jint n_ctx, jint n_threads, jint n_gpu_layers) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    add_app_log("INFO", (std::string("Loading: ") + p).c_str());
    add_app_log("INFO", (std::string("Config: n_ctx=") + std::to_string(n_ctx) +
                     " n_threads=" + std::to_string(n_threads) +
                     " n_gpu_layers=" + std::to_string(n_gpu_layers)).c_str());
    FILE* f = fopen(p, "rb");
    if (!f) { add_app_log("ERR", "Cannot open file"); env->ReleaseStringUTFChars(path, p); return 0; }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fclose(f);
    add_app_log("INFO", (std::string("Size: ") + std::to_string(sz/(1024*1024)) + " MB").c_str());
    env->ReleaseStringUTFChars(path, p);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;

    auto t0 = std::chrono::steady_clock::now();
    llama_model* model = llama_model_load_from_file(p, mp);
    long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();

    if (!model) { add_app_log("ERR", "Model load FAILED"); return 0; }
    add_app_log("INFO", (std::string("Loaded in ") + std::to_string(ms) + " ms").c_str());
    if (n_gpu_layers > 0) {
        add_app_log("INFO", (std::string("GPU layers: ") + std::to_string(n_gpu_layers) + " (Vulkan active)").c_str());
    } else {
        add_app_log("INFO", "GPU layers: 0 (CPU only)");
    }

    const llama_vocab* v = llama_model_get_vocab(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx; cp.n_batch = n_ctx;
    cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    cp.no_perf = true;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); add_app_log("ERR", "Context init FAILED"); return 0; }

    add_app_log("INFO", "Model ready");
    return reinterpret_cast<jlong>(new ModelHandles{model, ctx, (int32_t)llama_vocab_eos(v)});
}

JNIEXPORT void JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_freeModel(JNIEnv*, jobject, jlong h) {
    auto* s = reinterpret_cast<ModelHandles*>(h);
    if (s) {
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        delete s;
        llama_backend_free();
        add_app_log("INFO", "Model freed");
    }
}

/** Check if text looks like a single word (no spaces/punctuation, short) */
static bool is_single_word(const std::string& t) {
    size_t start = t.find_first_not_of(" \t\n\r");
    size_t end = t.find_last_not_of(" \t\n\r");
    if (start == std::string::npos) return false;
    std::string trimmed = t.substr(start, end - start + 1);
    bool hasSpace = trimmed.find(' ') != std::string::npos;
    // sentence punctuation (including Chinese chars as string search)
    bool hasSentence = trimmed.find('.') != std::string::npos ||
                       trimmed.find(',') != std::string::npos ||
                       trimmed.find('!') != std::string::npos ||
                       trimmed.find('?') != std::string::npos ||
                       trimmed.find("\xe3\x80\x82") != std::string::npos ||  // 。
                       trimmed.find("\xef\xbc\x8c") != std::string::npos ||  // ，
                       trimmed.find("\xef\xbc\x81") != std::string::npos ||  // ！
                       trimmed.find("\xef\xbc\x9f") != std::string::npos;    // ？
    return !hasSpace && !hasSentence && trimmed.size() <= 30;
}

/**
 * Build Qwen2.5 chat-format prompt: <|im_start|>system\n{sys}\n<|im_end|>\n<|im_start|>user\n{user}\n<|im_end|>\n<|im_start|>assistant\n
 */
static std::string qwen_prompt(const std::string& sys, const std::string& user) {
    return "<|im_start|>system\n" + sys + "\n<|im_end|>\n"
           "<|im_start|>user\n" + user + "\n<|im_end|>\n"
           "<|im_start|>assistant\n";
}

/**
 * Unified generate: mode 0=EN→CN, 1=CN→EN, 2=Chat
 * Returns: pure result text (stats logged only)
 */
JNIEXPORT jstring JNICALL Java_com_moyue_app_localai_LlamaJniWrapper_generate(
        JNIEnv* env, jobject, jlong h, jstring jtext, jint mode, jint max_tokens) {
    auto* s = reinterpret_cast<ModelHandles*>(h);
    if (!s || !s->ctx) return env->NewStringUTF("[ERR] model not loaded");

    const char* ct = env->GetStringUTFChars(jtext, nullptr);
    std::string text(ct);
    env->ReleaseStringUTFChars(jtext, ct);

    std::string prompt;
    float temp, penalty;
    int effective_max = max_tokens;
    switch (mode) {
        case 0: { // EN→CN
            bool word = is_single_word(text);
            if (word) {
                prompt = qwen_prompt(
                    "你是英汉词典。给出：音标、简短中文释义、一个例句及翻译。每项一行，不要多话。",
                    text);
            } else {
                prompt = qwen_prompt(
                    "把以下英文翻译成中文，只输出译文。",
                    text);
            }
            temp = 0.3f; penalty = 1.2f;
            break;
        }
        case 1: { // CN→EN
            bool word = is_single_word(text);
            if (word) {
                prompt = qwen_prompt(
                    "你是汉英词典。给出：拼音、简短英文释义、一个例句及翻译。每项一行，不要多话。",
                    text);
            } else {
                prompt = qwen_prompt(
                    "把以下中文翻译成英文，只输出译文。",
                    text);
            }
            temp = 0.3f; penalty = 1.2f;
            break;
        }
        case 2: // Chat
            prompt = qwen_prompt(
                "你是简洁的AI助手。简短回答，不重复。",
                text);
            temp = 0.6f; penalty = 1.3f; break;
        default:
            prompt = text; temp = 0.5f; penalty = 1.1f; break;
    }

    add_app_log("INFO", (std::string("Mode=") + std::to_string(mode) +
                      " prompt=" + std::to_string(prompt.size()) + "B").c_str());

    llama_kv_self_clear(s->ctx);

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

    for (int i = 0; i < effective_max; i++) {
        auto s0 = std::chrono::steady_clock::now();
        int ret = llama_decode(s->ctx, batch);
        long dms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - s0).count();
        if (i == 0) { first_ms = dms; add_app_log("INFO", (std::string("First: ") + std::to_string(dms) + "ms").c_str()); }
        if (ret != 0) { add_app_log("ERR", (std::string("decode: ") + std::to_string(ret)).c_str()); break; }
        gen++;

        llama_token id = llama_sampler_sample(sm, s->ctx, -1);
        if ((int32_t)id == s->eos_token) { add_app_log("INFO", "EOS"); break; }

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
        if (tok_repeat) { add_app_log("WARN", "Token repeat, stopping"); repeat_stop = true; break; }

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
                        add_app_log("WARN", "Phrase repeat, stopping"); repeat_stop = true; break;
                    }
                    while (recent_phrases.size() > 6) recent_phrases.erase(recent_phrases.begin());
                }
            }
        }
        batch = llama_batch_get_one(&id, 1);
    }

    long total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    llama_kv_self_clear(s->ctx);
    llama_sampler_free(sm);

    float spd = total_ms > 0 ? gen * 1000.0f / total_ms : 0;
    std::string tag = repeat_stop ? "[TRUNC] " : "";

    // Clean perf summary for UI
    std::string perf = tag + std::to_string(gen) + "tok | " +
        std::to_string(total_ms) + "ms total | " +
        std::to_string(first_ms) + "ms 首字 | " +
        std::to_string((int)(spd*10)/10.0f) + "tok/s";
    add_app_log("INFO", perf.c_str());

    return safe_jstring(env, safe_utf8(result));
}

} // extern "C"
