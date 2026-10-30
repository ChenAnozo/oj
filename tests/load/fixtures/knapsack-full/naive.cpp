#include <algorithm>
#include <iostream>
#include <vector>

std::vector<int> weights, values;
// Deliberately exponential reference for verifying that full cases reject DFS.
int search(int count, int capacity) {
    if (count == 0) return 0;
    int result = search(count - 1, capacity);
    if (weights[count - 1] <= capacity)
        result = std::max(result, values[count - 1] + search(count - 1, capacity - weights[count - 1]));
    return result;
}
int main() {
    int n, capacity;
    std::cin >> n >> capacity;
    weights.resize(n); values.resize(n);
    for (int i = 0; i < n; ++i) std::cin >> weights[i] >> values[i];
    std::cout << search(n, capacity) << std::endl;
}
