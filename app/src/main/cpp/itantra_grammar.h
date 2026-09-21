#ifndef ITANTRA_GRAMMAR_H
#define ITANTRA_GRAMMAR_H

#include <stddef.h>
#include "whisper.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct itantra_grammar itantra_grammar;

itantra_grammar *itantra_grammar_parse(const char *gbnf);

const whisper_grammar_element **itantra_grammar_rules(itantra_grammar *grammar);
size_t itantra_grammar_n_rules(const itantra_grammar *grammar);
size_t itantra_grammar_start_rule(const itantra_grammar *grammar);

void itantra_grammar_free(itantra_grammar *grammar);

#ifdef __cplusplus
}
#endif

#endif
