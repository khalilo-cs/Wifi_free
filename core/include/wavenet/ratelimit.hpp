// Token-bucket rate limiter keyed by string (e.g. remote IP). Bounded memory.
#pragma once
#include <cstdint>
#include <map>
#include <mutex>
#include <string>

namespace wavenet {

class RateLimiter {
 public:
  // `burst` tokens max, refilled at `per_second`. Tracks at most `max_keys` keys (oldest-updated evicted).
  RateLimiter(double burst, double per_second, size_t max_keys = 4096)
      : burst_(burst), rate_(per_second), max_keys_(max_keys) {}
  // Consumes one token for `key` at time `now_ms`. Returns false if none left.
  bool allow(const std::string& key, uint64_t now_ms);
  size_t tracked() const { std::lock_guard lk(mu_); return b_.size(); }

 private:
  struct Bucket { double tokens; uint64_t last_ms; };
  double burst_, rate_;
  size_t max_keys_;
  mutable std::mutex mu_;
  std::map<std::string, Bucket> b_;
};

}  // namespace wavenet
