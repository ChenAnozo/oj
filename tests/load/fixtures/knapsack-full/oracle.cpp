#include <algorithm>
#include <iostream>
#include <vector>

// Previous-row oracle, independent of the submitted in-place descending DP.
int main() {
    int n, capacity;
    std::cin >> n >> capacity;
    std::vector<int> previous(capacity + 1), current(capacity + 1);
    while (n--) {
        int w, v;
        std::cin >> w >> v;
        current = previous;
        for (int c = w; c <= capacity; ++c)
            current[c] = std::max(previous[c], previous[c - w] + v);
        previous.swap(current);
    }
    std::cout << previous[capacity] << std::endl;
}
