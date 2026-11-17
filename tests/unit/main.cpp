#include "test.hpp"
#include <cstring>
int main(int argc, char** argv) {
  const char* filter = argc > 1 ? argv[1] : nullptr;
  int run = 0;
  for (auto& c : wt::registry()) {
    if (filter && !std::strstr(c.name, filter)) continue;
    int before = wt::failures();
    try { c.fn(); } catch (const std::exception& e) { std::fprintf(stderr, "  EXCEPTION in %s: %s\n", c.name, e.what()); ++wt::failures(); }
    std::printf("%s %s\n", wt::failures() == before ? "[ OK ]" : "[FAIL]", c.name);
    ++run;
  }
  std::printf("%d tests, %d failed checks\n", run, wt::failures());
  return wt::failures() == 0 ? 0 : 1;
}
