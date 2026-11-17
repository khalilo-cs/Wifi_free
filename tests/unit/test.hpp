// Minimal test harness (no external dependency).
#pragma once
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace wt {
struct Case { const char* name; std::function<void()> fn; };
inline std::vector<Case>& registry() { static std::vector<Case> r; return r; }
inline int& failures() { static int f = 0; return f; }
struct Reg { Reg(const char* n, std::function<void()> f) { registry().push_back({n, std::move(f)}); } };
}  // namespace wt

#define WT_CAT2(a, b) a##b
#define WT_CAT(a, b) WT_CAT2(a, b)
#define TEST(name) static void name(); static wt::Reg WT_CAT(reg_, name)(#name, name); static void name()
#define CHECK(cond) do { if (!(cond)) { std::fprintf(stderr, "  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); ++wt::failures(); } } while (0)
#define CHECK_EQ(a, b) do { if (!((a) == (b))) { std::fprintf(stderr, "  FAIL %s:%d: %s == %s\n", __FILE__, __LINE__, #a, #b); ++wt::failures(); } } while (0)
#define CHECK_THROWS(expr) do { bool t_ = false; try { (void)(expr); } catch (...) { t_ = true; } if (!t_) { std::fprintf(stderr, "  FAIL %s:%d: expected throw: %s\n", __FILE__, __LINE__, #expr); ++wt::failures(); } } while (0)
