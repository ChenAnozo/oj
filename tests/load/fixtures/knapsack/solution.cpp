#include <algorithm>
#include <iostream>
#include <vector>

int main() {
    std::ios::sync_with_stdio(false);
    std::cin.tie(nullptr);
    int n, capacity;
    if (!(std::cin >> n >> capacity)) return 1;
    std::vector<int> dp(capacity + 1, 0);
    for (int i = 0; i < n; ++i) {
        int weight, value;
        std::cin >> weight >> value;
        for (int c = capacity; c >= weight; --c) {
            dp[c] = std::max(dp[c], dp[c - weight] + value);
        }
    }
    std::cout << dp[capacity] << '\n';
}
