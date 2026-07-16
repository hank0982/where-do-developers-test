from torch.utils.data import TensorDataset
import numpy as np
import logging
import os
import random
import torch
import time
import csv
import sys
import json
import glob
from tqdm import tqdm
import pandas as pd
import numpy as np
from sklearn.metrics import f1_score,precision_score,recall_score,confusion_matrix

# from _utils import *

csv.field_size_limit(sys.maxsize)

logger = logging.getLogger(__name__)

class InputExample(object):
    """A single training/test example for simple sequence classification."""

    def __init__(self, id,test_path, name, body, invo, signature,label=None):
        """Constructs a InputExample."""
        # self.guid = guid
        self.id = id
        self.test_path = test_path
        self.name = name
        self.body = body
        self.invo = invo
        self.signature = signature
        self.label = label or []

class InputFeatures(object):
    """A single set of features of data."""
    
    def __init__(self, input_ids, label_id):
        self.input_ids = input_ids
        self.label_id = label_id


def read_all_json_files(projects_dir):
    examples = []
    logger.info("LOOKING AT {}".format(os.path.join(projects_dir)))
    json_files = sorted(glob.glob(os.path.join(projects_dir, '*.json')))

    for json_file in json_files:
        with open(json_file, 'r', encoding='utf-8') as reader:
            raw = reader.read().strip()

        if not raw:
            continue

        try:
            payloads = [json.loads(raw)]
        except json.JSONDecodeError:
            payloads = [json.loads(line) for line in raw.splitlines() if line.strip()]

        for js in payloads:
            method = js['body']
            name, body = js['test_name'], method

            examples.append(
                InputExample(
                    id=js['id'],
                    test_path=js.get('test_path', ''),
                    name=name,
                    body=body,
                    invo=js.get('invocations', []),
                    signature=js.get('signature', {}),
                    label=js.get('label', [])
                )
            )
    return examples


def read_csv_examples(filepath, data_num, dataset_type):
    examples = []
    data = pd.read_csv(filepath)
    for idx,row in data.iterrows():
        guid = "%s-%s" % (dataset_type, idx)
        body = row['body'] if isinstance(row['body'],str) else str(row['body'])
        invo = row['invocation'] if isinstance(row['invocation'],str) else str(row['invocation'])
        label = int(row['label'])
        
        examples.append(
            InputExample(
                guid=guid, 
                body=body, 
                invo=invo, 
                label_id=label)
                )

    return examples

def convert_cls_examples_to_features(item):
    example, example_index, tokenizer, args = item
    # rewrite for code body and invocation
    # tokens_body = tokenizer.tokenize(example.body)
    # tokens_invo = tokenizer.tokenize(example.invo)
    
    # logger.info("Running unixcoder-base model")
    # 优先保证invocation全部进入模型
    # cat_tokens = [tokenizer.cls_token] + tokens_invo + [tokenizer.sep_token] + tokens_body
    # cat_tokens = [tokenizer.cls_token] + tokens_invo + tokens_body
    # input_tokens = cat_tokens[:args.max_source_length-1] + [tokenizer.sep_token]
    # input_ids = tokenizer.convert_tokens_to_ids(input_tokens)
    # padding_length = args.max_source_length - len(input_ids)
    # input_ids += [tokenizer.pad_token_id]*padding_length

    source_str = example.invo + example.body.replace(example.name,'[MASK]')
    print(source_str)
    source_str = source_str.replace('</s>', '<unk>')
    source_ids = tokenizer.encode(source_str, max_length=args.max_source_length, padding='max_length', truncation=True)
    assert source_ids.count(tokenizer.eos_token_id) == 1

    return InputFeatures(input_ids=source_ids,
                    label_id=example.label_id)

def load_and_cache_cls_data(args, filename, pool, tokenizer, split_tag, is_sample=False):
    cache_fn = os.path.join(args.cache_path, split_tag)
    filepath = os.path.join(args.data_dir,filename)
    examples = read_csv_examples(filepath, args.data_num, split_tag)
    if is_sample:
        examples = random.sample(examples, int(len(examples) * 0.1))

    if os.path.exists(cache_fn):
        logger.info("Load cache data from %s", cache_fn)
        data = torch.load(cache_fn)
    else:
        if is_sample:
            logger.info("Sample 10 percent of data from %s", filename)
        elif args.data_num == -1:
            logger.info("Create cache data into %s", cache_fn)
            
        tuple_examples = [(example, idx, tokenizer, args) for idx, example in enumerate(examples)]
        features = pool.map(convert_cls_examples_to_features, tqdm(tuple_examples, total=len(tuple_examples)))
        # features = [convert_clone_examples_to_features(x) for x in tuple_examples]
        all_source_ids = torch.tensor([f.input_ids for f in features], dtype=torch.long)
        all_labels = torch.tensor([f.label_id for f in features], dtype=torch.long)
        data = TensorDataset(all_source_ids, all_labels)

        if args.local_rank in [-1, 0] and args.data_num == -1:
            torch.save(data, cache_fn)
    return examples, data

def calc_stats(examples, tokenizer=None, is_tokenize=False):
    avg_src_len = []
    avg_trg_len = []
    avg_src_len_tokenize = []
    avg_trg_len_tokenize = []
    for ex in examples:
        if is_tokenize:
            avg_src_len.append(len(ex.source.split()))
            avg_trg_len.append(len(str(ex.target).split()))
            avg_src_len_tokenize.append(len(tokenizer.tokenize(ex.source)))
            avg_trg_len_tokenize.append(len(tokenizer.tokenize(str(ex.target))))
        else:
            avg_src_len.append(len(ex.source.split()))
            avg_trg_len.append(len(str(ex.target).split()))
    if is_tokenize:
        logger.info("Read %d examples, avg src len: %d, avg trg len: %d, max src len: %d, max trg len: %d",
                    len(examples), np.mean(avg_src_len), np.mean(avg_trg_len), max(avg_src_len), max(avg_trg_len))
        logger.info("[TOKENIZE] avg src len: %d, avg trg len: %d, max src len: %d, max trg len: %d",
                    np.mean(avg_src_len_tokenize), np.mean(avg_trg_len_tokenize), max(avg_src_len_tokenize),
                    max(avg_trg_len_tokenize))
    else:
        logger.info("Read %d examples, avg src len: %d, avg trg len: %d, max src len: %d, max trg len: %d",
                    len(examples), np.mean(avg_src_len), np.mean(avg_trg_len), max(avg_src_len), max(avg_trg_len))


def get_elapse_time(t0):
    elapse_time = time.time() - t0
    if elapse_time > 3600:
        hour = int(elapse_time // 3600)
        minute = int((elapse_time % 3600) // 60)
        return "{}h{}m".format(hour, minute)
    else:
        minute = int((elapse_time % 3600) // 60)
        return "{}m".format(minute)

def simple_accuracy(preds, labels):
    return (preds == labels).mean()

def acc_and_f1(preds, labels):
    # print(preds)
    # print(labels)

    acc = simple_accuracy(preds, labels)
    tn, fp, fn, tp = confusion_matrix(y_true=labels,y_pred=preds).ravel()
    f1 = f1_score(y_true=labels, y_pred=preds)
    precision = precision_score(y_true=labels,y_pred=preds)
    recall = recall_score(y_true=labels,y_pred=preds)
    return {
        "acc": acc,
        "precision":precision,
        "recall":recall,
        "f1": f1,
        "acc_and_f1": (acc + f1) / 2,
        "tp":int(tp),
        "fp":int(fp),
        "fn":int(fn),
        "tn":int(tn)
    }
