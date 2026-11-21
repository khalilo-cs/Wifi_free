#include "wavenet/ratelimit.hpp"
#include <algorithm>

namespace wavenet {

bool RateLimiter::allow(const std::string& key, uint64_t now) {
  std::lock_guard lk(mu_);
  auto it = b_.find(key);
  if (it == b_.end()) {
    if (b_.size() >= max_keys_) {  // evict least recently updated bucket
      auto old = std::min_element(b_.begin(), b_.end(), [](auto& x, auto& y) { return x.second.last_ms < y.second.last_ms; });
      b_.erase(old);
    }
    it = b_.emplace(key, Bucket{burst_, now}).first;
  }
  Bucket& b = it->second;
  if (now > b.last_ms) b.tokens = std::min(burst_, b.tokens + double(now - b.last_ms) / 1000.0 * rate_);
  b.last_ms = std::max(b.last_ms, now);
  if (b.tokens < 1.0) return false;
  b.tokens -= 1.0;
  return true;
}

}  // namespace wavenet
