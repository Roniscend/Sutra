#include "itantra_grammar.h"

#include <android/log.h>
#include <new>
#include <vector>

#include "grammar-parser.h"

#define TAG "iTantraWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct itantra_grammar {
    grammar_parser::parse_state state;
    std::vector<const whisper_grammar_element *> rules;
    size_t start_rule = 0;
};

extern "C" itantra_grammar *itantra_grammar_parse(const char *gbnf) {
    if (gbnf == nullptr) return nullptr;

    auto *grammar = new (std::nothrow) itantra_grammar();
    if (grammar == nullptr) return nullptr;

    grammar->state = grammar_parser::parse(gbnf);
    if (grammar->state.rules.empty()) {
        LOGE("grammar parse produced no rules");
        delete grammar;
        return nullptr;
    }

    const auto root = grammar->state.symbol_ids.find("root");
    if (root == grammar->state.symbol_ids.end()) {
        LOGE("grammar has no root rule");
        delete grammar;
        return nullptr;
    }

    grammar->start_rule = root->second;
    grammar->rules = grammar->state.c_rules();
    LOGI("grammar parsed: %d rules, root=%d",
         (int) grammar->rules.size(), (int) grammar->start_rule);
    return grammar;
}

extern "C" const whisper_grammar_element **itantra_grammar_rules(
        itantra_grammar *grammar) {
    return grammar == nullptr ? nullptr : grammar->rules.data();
}

extern "C" size_t itantra_grammar_n_rules(const itantra_grammar *grammar) {
    return grammar == nullptr ? 0 : grammar->rules.size();
}

extern "C" size_t itantra_grammar_start_rule(const itantra_grammar *grammar) {
    return grammar == nullptr ? 0 : grammar->start_rule;
}

extern "C" void itantra_grammar_free(itantra_grammar *grammar) {
    delete grammar;
}
