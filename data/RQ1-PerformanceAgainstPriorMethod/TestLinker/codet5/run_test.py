# coding=utf-8
# Copyright 2018 The Google AI Language Team Authors and The HuggingFace Inc. team.
# Copyright (c) 2018, NVIDIA CORPORATION.  All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
Fine-tuning the library models for language modeling on a text file (GPT, GPT-2, BERT, RoBERTa).
GPT and GPT-2 are fine-tuned using a causal language modeling (CLM) loss while BERT and RoBERTa are fine-tuned
using a masked language modeling (MLM) loss.
"""

from __future__ import absolute_import
import os
import logging
import argparse
import math
import numpy as np
from io import open
from tqdm import tqdm
import json
import torch
import traceback
import jsonlines
from tensorboardX import SummaryWriter
from torch.utils.data import DataLoader, Dataset, SequentialSampler, RandomSampler, TensorDataset
from torch.utils.data.distributed import DistributedSampler
from transformers import (WEIGHTS_NAME, AdamW, get_linear_schedule_with_warmup,
                          RobertaConfig, RobertaModel, RobertaTokenizer,
                          BartConfig, BartForConditionalGeneration, BartTokenizer,
                          T5Config, T5ForConditionalGeneration, T5Tokenizer)
import multiprocessing
import time
import re
from models import DefectModel
from configs import add_args, set_seed
from utils import get_elapse_time, load_and_cache_cls_data,acc_and_f1,read_all_json_files
from models import get_model_size

MODEL_CLASSES = {'roberta': (RobertaConfig, RobertaModel, RobertaTokenizer),
                 't5': (T5Config, T5ForConditionalGeneration, T5Tokenizer),
                 'codet5': (T5Config, T5ForConditionalGeneration, RobertaTokenizer),
                 'bart': (BartConfig, BartForConditionalGeneration, BartTokenizer)}

cpu_cont = multiprocessing.cpu_count()

logging.basicConfig(format='%(asctime)s - %(levelname)s - %(name)s -   %(message)s',
                    datefmt='%m/%d/%Y %H:%M:%S',
                    level=logging.INFO)
logger = logging.getLogger(__name__)

class CupTokenizer:
    @classmethod
    def camel_case_split(cls, identifier):
        return re.sub(r'([A-Z][a-z])', r' \1', re.sub(r'([A-Z]+)', r' \1', identifier)).strip().split()

    @classmethod
    def tokenize_identifier_raw(cls, token, keep_underscore=False):
        regex = r'(_+)' if keep_underscore else r'_+'
        id_tokens = []
        for t in re.split(regex, token):
            if t:
                id_tokens += cls.camel_case_split(t)
        id_tokens = [x.lower() for x in id_tokens]
        return list(filter(lambda x: len(x) > 0, id_tokens))

CUP_TOKENIZER = CupTokenizer()

def get_recom_sigs_by_name(example, inference_only=False):
    recom_sigs = []

    # 先判断是否使用规则
    prefix_list = ['testCases','TestCases','testcases','testCase','TestCase','testcase', 'tests', 'Tests','test_','test', 'Test']
    test_name = None
    for prefix in prefix_list:
        if prefix in example.name:
            test_name = example.name.replace(prefix,'')
            test_name_tokens = CUP_TOKENIZER.tokenize_identifier_raw(test_name)
            break

    if test_name is None:
       return False,None,None,None,None
    
    pre_cnt,recall_cnt,recom_cnt= 0,0,0
    labels = example.label or []
    signatures = example.signature or {}

    #  如果sig为0的话，构造一个无参数的推荐，比较和label是否一致
    if len(signatures) == 0:
        test_path = example.test_path
        test_path_remove_prefix = test_path
        for prefix in prefix_list:
            if prefix in test_path:
                test_path_remove_prefix = test_path.replace(prefix,'')
                break
        recom_sig = test_path_remove_prefix.replace('/','.') + "." + test_name[0].lower() + test_name[1:]
        print(recom_sig)
        recom_sigs.append(recom_sig)
        recom_sigs = list(set(recom_sigs))

        if not recom_sigs:
            return False,None,None,None,None

        if inference_only:
            return True,pre_cnt,recall_cnt,len(recom_sigs),recom_sigs

        non_param_label_list = []
        for label in labels:
            match = re.search(r"([^()]*)", label)
            if match:
                non_param_label = match.group(1)
                # print(non_param_label)
                non_param_label_list.append(non_param_label)
        
        recom_sigs_set = set(recom_sigs)
        labels_set = set(non_param_label_list)
        if recom_sigs_set & labels_set: 
             #计算pre_cnt, 即推荐的sig中，有多少是正确的
            for recom_sig in recom_sigs:
                if recom_sig in non_param_label_list:
                    pre_cnt += 1

            # 计算recall_cnt, 即label中有多少被正确找到
            for label in non_param_label_list:
                if label in recom_sigs:
                    recall_cnt += 1

            recom_cnt += len(recom_sigs)

            return True,pre_cnt,recall_cnt,recom_cnt,recom_sigs
        else:
            return False,None,None,None,None
        
    else:
        for k in signatures.keys():
            pattern = r'(\w+(?=\s*\())'
            match = re.search(pattern,k)
            # print(k)
            if match:
                # print(match.group(0))
                production = match.group(0)
                production_tokens = CUP_TOKENIZER.tokenize_identifier_raw(production)
                # print(production_tokens)
            else:
                continue
            if production_tokens == test_name_tokens:
                if len(signatures[k]['detail_sigs']) > 0:
                    recom_sigs.extend(signatures[k]['detail_sigs'])
                else:
                    recom_sigs.append(k)
            else:
                continue
        # 去重
        recom_sigs = list(set(recom_sigs))

        if not recom_sigs:
            return False,None,None,None,None

        if inference_only:
            return True, pre_cnt,recall_cnt,len(recom_sigs),recom_sigs

        recom_sigs_set = set(recom_sigs)
        labels_set = set(labels)

        if recom_sigs_set & labels_set:
            #计算pre_cnt, 即推荐的sig中，有多少是正确的
            for recom_sig in recom_sigs:
                if recom_sig in labels:
                    pre_cnt += 1
            # 计算recall_cnt, 即label中有多少被正确找到
            for label in labels:
                if label in recom_sigs:
                    recall_cnt += 1
            recom_cnt += len(recom_sigs)

            return True, pre_cnt,recall_cnt,recom_cnt,recom_sigs
        else:
            return False,None,None,None,None


def get_recom_sigs_by_model(args, model, tokenizer, example):
    body = example.body
    pre_cnt,recall_cnt,recom_cnt = 0,0,0
    labels = example.label or []

    invocations = example.invo
    if len(invocations) == 0:
        return pre_cnt,recall_cnt,recom_cnt,[],[]
        
    all_inputs_ids = []
    all_idx = []
    numbers_to_invocations = {}
    invocations_to_numbers = {}
    for idx,invo in enumerate(invocations):
        numbers_to_invocations[int(idx)] = invo
        invocations_to_numbers[invo] = int(idx)
        try:
            source_str = invo + body.replace(example.name,'[MASK]')
            source_str = invo + body
            # print(source_str)
            source_str = source_str.replace('</s>', '<unk>')
            source_ids = tokenizer.encode(source_str, max_length=args.max_source_length, padding='max_length', truncation=True)
            assert source_ids.count(tokenizer.eos_token_id) == 1
        
            all_inputs_ids.append(source_ids)
            all_idx.append(idx)
        except Exception as e:
            # 捕获异常并打印错误详情
            raise ValueError("convert to features error!!!")
            traceback.print_exc()
        
    all_inputs_ids = torch.tensor(all_inputs_ids,dtype=torch.long)
    # print(all_inputs_ids.shape)
    # all_idx  = torch.tensor(all_idx,dtype=torch.long)
    all_labels = torch.tensor([0 for i in range(len(invocations))],dtype=torch.long)
    eval_dataset = TensorDataset(all_inputs_ids,all_labels)

    # Note that DistributedSampler samples randomly
    eval_sampler = SequentialSampler(eval_dataset)
    eval_dataloader = DataLoader(eval_dataset, sampler=eval_sampler, batch_size=args.eval_batch_size, num_workers=4 * args.n_gpu, pin_memory=True)

    preds = None
    nb_eval_steps = 0
    logits = []
    model.eval()
    # out_idx = None
    for batch in eval_dataloader:
        inputs = batch[0].to(args.device)
        label = batch[1].to(args.device)
        with torch.no_grad():
            lm_loss, logit = model(inputs, label)
            logits.append(logit.cpu().numpy())
        nb_eval_steps += 1    
        
    preds = np.concatenate(logits, 0)

    result = {}
    for i in range(len(all_idx)):
        pred_idx = int(all_idx[i])
        pred_score_1 = preds[i][1]
        result[pred_idx] = pred_score_1

    # print(result)
    sorted_result =  sorted(result.items(), key=lambda item: item[1],reverse=True)
    # print(sorted_result)
    sorted_invos = []
    for k,v in sorted_result:
        sorted_invos.append(numbers_to_invocations[k])

    # 选取 top K 个invo作为推荐
    recom_invos = sorted_invos[:args.top_k]
    # 根据推荐的invo查找对应的signature
    signatures = example.signature
    recom_sigs = []

    def collect_sigs_for_invocation(recom_invo):
        matched_sigs = []
        for sig in signatures.keys():
            invo = sig.split('(')[0].split('.')[-1]
            if recom_invo == invo:
                if len(signatures[sig]['detail_sigs']) > 0:     #如果有映射的话，就使用detail
                    matched_sigs.extend(signatures[sig]['detail_sigs'])
                else:    #如果没有映射的话，就使用sig
                    matched_sigs.append(sig)
        return matched_sigs

    for recom_invo in recom_invos:
        recom_sigs.extend(collect_sigs_for_invocation(recom_invo))

    # If the top-ranked invocation has no recoverable signature, fall through
    # the remaining ranked invocation list until we find a focal signature.
    if not recom_sigs:
        for fallback_invo in sorted_invos[args.top_k:]:
            fallback_sigs = collect_sigs_for_invocation(fallback_invo)
            if fallback_sigs:
                recom_sigs.extend(fallback_sigs)
                break

    # 去重
    recom_sigs = list(set(recom_sigs))

    if args.inference_only:
        return pre_cnt,recall_cnt,len(recom_sigs), recom_sigs,sorted_invos

    #计算pre_cnt, 即推荐的sig中，有多少是正确的
    for recom_sig in recom_sigs:
        if recom_sig in labels:
            pre_cnt += 1
    # 计算recall_cnt, 即label中有多少被正确找到
    for label in labels:
        if label in recom_sigs:
            recall_cnt += 1
    recom_cnt += len(recom_sigs)

    return pre_cnt,recall_cnt,recom_cnt, recom_sigs,sorted_invos

def test(args, model, tokenizer):

    save_dir = args.res_dir
    if not os.path.exists(save_dir):
        os.mkdir(save_dir)

    #  read data
    TOTAL_RULE_CNT = 0
    TOTAL_MODEL_CNT = 0
    TOTAL_PRE_CNT = 0
    TOTAL_RECALL_CNT = 0
    TOTAL_LABEL_CNT = 0
    TOTAL_RECOM_CNT = 0
    EVAL_RESULT = {}
    TOTAL_TIME = 0
    TOTAL_EXAMPLE_CNT = 0
    TOTAL_NOINVO_CNT = 0
    TOTAL_NOINVO_LABEL_CNT = 0

    if args.eval_all_projects:
        projects = [f for f in os.listdir(args.projects_dir) if os.path.isdir(os.path.join(args.projects_dir, f))]

    for project in projects:
        project_dir = os.path.join(args.projects_dir,project)
        # 读取下面的所有json文件，返回examples
        examples = read_all_json_files(project_dir)

        # 每一个项目下面的的值
        rule_cnt = 0
        model_cnt = 0
        pre_cnt = 0
        recall_cnt = 0
        label_cnt = 0
        recom_cnt = 0
        noinvo_cnt = 0
        noinvo_label_cnt = 0
        eval_result = {}

        for example in tqdm(examples,desc="total examples of {}".format(project)):
            # print("=="*50)
            sorted_invos = None
            recom_by = None
            recom_sigs = None
            labels = example.label or []
            if not args.inference_only:
                label_cnt += len(labels)

            if len(example.invo)==0:
                noinvo_cnt += 1
                if not args.inference_only:
                    noinvo_label_cnt += len(labels)
            
            start_time = time.time()

            # 先使用规则
            if args.only_model:
                res_by_rule = [False,None,None,None,None]
            else:
                res_by_rule = get_recom_sigs_by_name(example, inference_only=args.inference_only)
            print('res_by_rule',res_by_rule)

            # 判断规则是否找到，找不到直接使用模型
            if res_by_rule[0]: 
                recom_by = 'rule'
                if not args.inference_only:
                    pre_cnt += res_by_rule[1]
                    recall_cnt += res_by_rule[2]
                    recom_cnt += res_by_rule[3]
                recom_sigs = res_by_rule[4]
            else:
                # 如果规则找不到
                res_by_model = get_recom_sigs_by_model(args, model, tokenizer, example)
                print('res_by_model',res_by_model)
                recom_by = "model"
                if not args.inference_only:
                    pre_cnt += res_by_model[0]
                    recall_cnt += res_by_model[1]
                    recom_cnt += res_by_model[2]
                recom_sigs = res_by_model[3]
                sorted_invos = res_by_model[4]

            end_time = time.time()
            inference_time = end_time - start_time
            TOTAL_TIME += inference_time
            TOTAL_EXAMPLE_CNT += 1

            if recom_by == 'rule':
                rule_cnt += 1
            else:
                model_cnt += 1
            
            # 保存预测的具体内容
            detail_dic = {}
            detail_dic['id'] = example.id
            detail_dic['test_name'] = example.name
            detail_dic['invocations'] = example.invo
            detail_dic['sorted_invocations'] = sorted_invos
            detail_dic['signatures'] = example.signature
            detail_dic['recom_signatures'] = recom_sigs
            detail_dic['recom_by'] = recom_by
            if not args.inference_only:
                detail_dic['labels'] = labels
                is_recom_all = True
                
                for label in labels:
                    if label in recom_sigs:
                        continue
                    else:
                        is_recom_all = False
                        break
                detail_dic['is_recom_all'] = is_recom_all
            
            with jsonlines.open(os.path.join(save_dir,project + "_detail.json"),'a') as writer:
                writer.write(detail_dic)
    
        if args.inference_only:
            eval_result['example_cnt'] = len(examples)
            eval_result['noinvo_cnt'] = noinvo_cnt
            eval_result['rule_cnt'] = rule_cnt
            eval_result['model_cnt'] = model_cnt
            with open(os.path.join(save_dir, project + "_summary.json"),'w') as writer:
                writer.write(json.dumps(eval_result))

            TOTAL_RULE_CNT += rule_cnt
            TOTAL_MODEL_CNT += model_cnt
            TOTAL_NOINVO_CNT += noinvo_cnt
            continue

        # 计算一个项目的precision, recall, f1
        precision = pre_cnt / recom_cnt
        recall = recall_cnt / label_cnt
        f1 = 2* precision * recall / (precision + recall)
        eval_result['recom_cnt'] = recom_cnt
        eval_result['label_cnt'] = label_cnt
        eval_result['pre_cnt'] = pre_cnt
        eval_result['recall_cnt'] = recall_cnt
        eval_result['precision'] = precision
        eval_result['recall'] = recall
        eval_result['f1'] = f1
        eval_result['noinvo_cnt'] = noinvo_cnt
        eval_result['noinvo_label_cnt'] = noinvo_label_cnt
        eval_result['rule_cnt'] = rule_cnt
        eval_result['model_cnt'] = model_cnt

        # 保存结果
        with open(os.path.join(save_dir, project + "_mertrics.json"),'w') as writer:
            writer.write(json.dumps(eval_result))

        TOTAL_RULE_CNT += rule_cnt
        TOTAL_MODEL_CNT += model_cnt
        TOTAL_PRE_CNT += pre_cnt
        TOTAL_RECALL_CNT += recall_cnt
        TOTAL_RECOM_CNT += recom_cnt
        TOTAL_LABEL_CNT += label_cnt
        TOTAL_NOINVO_CNT += noinvo_cnt
        TOTAL_NOINVO_LABEL_CNT += noinvo_label_cnt

    if args.inference_only:
        EVAL_RESULT['rule_cnt'] = TOTAL_RULE_CNT
        EVAL_RESULT['model_cnt'] = TOTAL_MODEL_CNT
        EVAL_RESULT['noinvo_cnt'] = TOTAL_NOINVO_CNT
        EVAL_RESULT['total_time'] = TOTAL_TIME
        EVAL_RESULT['total_cnt'] = TOTAL_EXAMPLE_CNT
        EVAL_RESULT['avg_time'] = TOTAL_TIME / TOTAL_EXAMPLE_CNT if TOTAL_EXAMPLE_CNT else 0
        with open(os.path.join(save_dir, "all_inference_summary.json"),'w') as writer:
            writer.write(json.dumps(EVAL_RESULT))
        return

    # 计算所有项目的precision, recall, f1
    TOTAL_PRECISION = TOTAL_PRE_CNT / TOTAL_RECOM_CNT
    TOTAL_RECALL = TOTAL_RECALL_CNT / TOTAL_LABEL_CNT
    TOTAL_F1 = 2*TOTAL_PRECISION*TOTAL_RECALL / (TOTAL_PRECISION+TOTAL_RECALL)
    EVAL_RESULT['recom_cnt'] = TOTAL_RECOM_CNT
    EVAL_RESULT['label_cnt'] = TOTAL_LABEL_CNT
    EVAL_RESULT['pre_cnt'] = TOTAL_PRE_CNT
    EVAL_RESULT['recall_cnt'] = TOTAL_RECALL_CNT
    EVAL_RESULT['precision'] = TOTAL_PRECISION
    EVAL_RESULT['recall'] = TOTAL_RECALL
    EVAL_RESULT['f1'] = TOTAL_F1
    EVAL_RESULT['noinvo_cnt'] = TOTAL_NOINVO_CNT
    EVAL_RESULT['noinvo_label_cnt'] = TOTAL_NOINVO_LABEL_CNT
    EVAL_RESULT['rule_cnt'] = TOTAL_RULE_CNT
    EVAL_RESULT['model_cnt'] = TOTAL_MODEL_CNT
    EVAL_RESULT['total_time'] = TOTAL_TIME
    EVAL_RESULT['total_cnt'] = TOTAL_EXAMPLE_CNT
    EVAL_RESULT['avg_time'] = TOTAL_TIME / TOTAL_EXAMPLE_CNT
    
    with open(os.path.join(save_dir, "all_eval_mertrics.json"),'w') as writer:
        writer.write(json.dumps(EVAL_RESULT))

def main():
    parser = argparse.ArgumentParser()
    t0 = time.time()
    args = add_args(parser)
    logger.info(args)

    # Setup CUDA, GPU & distributed training
    if args.local_rank == -1 or args.no_cuda:
        device = torch.device("cuda" if torch.cuda.is_available() and not args.no_cuda else "cpu")
        args.n_gpu = torch.cuda.device_count()
    else:  # Initializes the distributed backend which will take care of sychronizing nodes/GPUs
        torch.cuda.set_device(args.local_rank)
        device = torch.device("cuda", args.local_rank)
        torch.distributed.init_process_group(backend='nccl')
        args.n_gpu = 1

    logger.warning("Process rank: %s, device: %s, n_gpu: %s, distributed training: %s, cpu count: %d",
                   args.local_rank, device, args.n_gpu, bool(args.local_rank != -1), cpu_cont)
    args.device = device
    set_seed(args)

    # Build model
    config_class, model_class, tokenizer_class = MODEL_CLASSES[args.model_type]
    # config = config_class.from_pretrained(args.config_name if args.config_name else args.model_name_or_path)
    # model = model_class.from_pretrained(args.model_name_or_path)
    # tokenizer = tokenizer_class.from_pretrained(args.tokenizer_name)
    config = config_class.from_pretrained(args.model_name_or_path)
    model = model_class.from_pretrained(args.model_name_or_path)
    tokenizer = tokenizer_class.from_pretrained(args.model_name_or_path)

    model = DefectModel(model, config, tokenizer, args)
    logger.info("Finish loading model [%s] from %s", get_model_size(model), args.model_name_or_path)

    if args.load_model_path is not None:
        logger.info("Reload model from {}".format(args.load_model_path))
        model.load_state_dict(torch.load(args.load_model_path))

    model.to(device)

    pool = multiprocessing.Pool(cpu_cont)
    fa = open(os.path.join(args.output_dir, 'summary.log'), 'a+')

    # Prepare optimizer and schedule (linear warmup and decay)
    no_decay = ['bias', 'LayerNorm.weight']
    optimizer_grouped_parameters = [
        {'params': [p for n, p in model.named_parameters() if not any(nd in n for nd in no_decay)],
            'weight_decay': args.weight_decay},
        {'params': [p for n, p in model.named_parameters() if any(nd in n for nd in no_decay)], 'weight_decay': 0.0}
    ]
    optimizer = AdamW(optimizer_grouped_parameters, lr=args.learning_rate, eps=args.adam_epsilon)

    if args.fp16:
        try:
            from apex import amp
        except ImportError:
            raise ImportError("Please install apex from https://www.github.com/nvidia/apex to use fp16 training.")
        model, optimizer = amp.initialize(model, optimizer, opt_level=args.fp16_opt_level)

    if args.do_test:
        logger.info("  " + "***** Testing *****")
        logger.info("  Batch size = %d", args.eval_batch_size)

        file = os.path.join(args.checkpoints_dir, 'pytorch_model.bin')

        model = model.module if hasattr(model, 'module') else model
        model.load_state_dict(torch.load(file, map_location=args.device))

        if args.n_gpu > 1:
            # multi-gpu training
            model = torch.nn.DataParallel(model)
        start_time = time.time()
        test(args, model, tokenizer)
        end_time = time.time()
        print("total time: ", end_time - start_time)

            # break

    fa.close()


if __name__ == "__main__":
    main()
