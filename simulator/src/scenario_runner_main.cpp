#include <fstream>
#include <iostream>
#include <string>

#include "miezmerker/sim/scenario.hpp"
#include "miezmerker/sim/scenario_runner.hpp"

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "Usage: scenario-runner <scenario-file>\n";
        return 2;
    }

    std::ifstream file(argv[1]);
    if (!file) {
        std::cerr << "Failed to open scenario file: " << argv[1] << "\n";
        return 2;
    }

    std::string text((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());

    miezmerker::sim::Scenario scenario;
    auto err = miezmerker::sim::parse_scenario(text, scenario);
    if (!err.ok()) {
        std::cerr << "Parse error at line " << err.line << ": " << err.message << "\n";
        return 1;
    }

    miezmerker::sim::ScenarioRunner runner(scenario);
    return runner.run();
}