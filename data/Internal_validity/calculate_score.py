import json
from typing import Dict, Set


def load_file(path: str) -> Dict[str, Set[int]]:
    with open(path, "r") as f:
        data = json.load(f)

    result = {}
    for case in data:
        method_name = case.get("current_db_test_name", [])
        result[method_name] = set(case.get("selected_method_ids", []))

    return result


def precision(set_a: Set[int], set_b: Set[int]) -> float:
    if not set_a:
        return 0.0
    return len(set_a & set_b) / len(set_a)

def recall(set_a: Set[int], set_b: Set[int]) -> float:
    if not set_b:
        return 0.0
    return len(set_a & set_b) / len(set_b)


def compute_F1_average(
    ann1: Dict[str, Set[int]],
    ann2: Dict[str, Set[int]],
    common_keys: Set[str]
) -> float:
    total_score = 0.0
    for key in common_keys:
        p = precision(ann1[key], ann2[key])
        r = recall(ann1[key], ann2[key])
        if p + r == 0:
            f1 = 0.0
        else:
            f1 = 2 * (p * r) / (p + r)
        total_score += f1

    return total_score / len(common_keys)



def exact_set_match(set_a: Set[int], set_b: Set[int]) -> float:
    return 1.0 if set_a == set_b else 0.0

def compute_exact_set_match_average(
    ann1: Dict[str, Set[int]],
    ann2: Dict[str, Set[int]],
    common_keys: Set[str]
) -> float:
    total_score = 0.0
    for key in common_keys:
        score = exact_set_match(ann1[key], ann2[key])
        total_score += score

    return total_score / len(common_keys) if common_keys else 0.0


def jaccard(set_a: Set[int], set_b: Set[int]) -> float:
    if not set_a and not set_b:
        return 1.0
    if not set_a or not set_b:
        return 0.0

    intersection = len(set_a & set_b)
    union = len(set_a | set_b)

    return intersection / union


def compute_jaccard_average(
    ann1: Dict[str, Set[int]],
    ann2: Dict[str, Set[int]],
    common_keys: Set[str]
) -> float:
    total_score = 0.0
    for key in common_keys:
        score = jaccard(ann1[key], ann2[key])
        total_score += score

    return total_score / len(common_keys) if common_keys else 0.0



def masi(set_a: Set[int], set_b: Set[int]) -> float:
    jaccard_score = jaccard(set_a, set_b)

    # ---- MASI penalty factor ----
    if set_a == set_b:
        penalty = 1.0
    elif set_a.issubset(set_b) or set_b.issubset(set_a):
        penalty = 0.5
    elif len(set_a & set_b) > 0:
        penalty = 0.25
    else:
        penalty = 0.0

    return jaccard_score * penalty


def compute_masi_average(
    ann1: Dict[str, Set[int]],
    ann2: Dict[str, Set[int]],
    common_keys: Set[str]
) -> float:
    total_score = 0.0

    for key in common_keys:
        total_score += masi(ann1[key], ann2[key])

    return total_score / len(common_keys) if common_keys else 0.0


def main(file1_path: str, file2_path: str):
    ann1 = load_file(file1_path)
    ann2 = load_file(file2_path)

    common_keys = set(ann1.keys()) & set(ann2.keys())
    
    exact_set_match_average = compute_exact_set_match_average(ann1, ann2, common_keys)
    print(f"Average Exact Set Match: {exact_set_match_average:.4f}")

    jaccard_score_average = compute_jaccard_average(ann1, ann2, common_keys)
    print(f"Average Jaccard Similarity: {jaccard_score_average:.4f}")

    masi_score_average = compute_masi_average(ann1, ann2, common_keys)
    print(f"Average MASI Similarity: {masi_score_average:.4f}")

    F1_average = compute_F1_average(ann1, ann2, common_keys)
    print(f"Average F1 Score: {F1_average:.4f}")

    # Save the result to a text file
    with open("similarity_result.txt", "w") as f:
        f.write(f"Average Exact Set Match: {exact_set_match_average:.4f}\n")
        f.write(f"Average Jaccard Similarity: {jaccard_score_average:.4f}\n")
        f.write(f"Average MASI Similarity: {masi_score_average:.4f}\n")
        f.write(f"Average F1 Score: {F1_average:.4f}\n")


if __name__ == "__main__":
    main("coder1.json", "coder2.json")