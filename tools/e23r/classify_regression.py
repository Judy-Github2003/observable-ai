#!/usr/bin/env python3
"""Classify real unfiltered Gradle JUnit outcomes without changing PASS/FAIL.

Three archival formal source-digest mismatches are independently documented,
NEVER ignored, skipped, or relabeled as passing tests.
"""
from __future__ import annotations
import argparse, json, sys
from pathlib import Path
import xml.etree.ElementTree as ET

HISTORIC_REASONS = {
    'logic.e3.pack02.C8FormalExamTest': 'student code changed after freeze',
    'logic.e3.pack02.MRXFormalTest': 'MRXSpace.kt',
    'logic.e3.pack02.MRXRFormalTest': 'MRXREvidence.kt',
}

def classify(folder: Path):
    files=sorted(folder.rglob('TEST-*.xml'))
    if not files:
        raise ValueError('No JUnit XML; cannot claim regression occurred')
    summary={'total':0,'passed':0,'failed':0,'errors':0,'skipped':0,
             'archivalSourceMismatch':0,'otherFailures':0,'failures':[]}
    for file in files:
        root=ET.parse(file).getroot()
        suite=root.attrib.get('name','')
        for case in root.iter('testcase'):
            summary['total']+=1
            fail=case.find('failure')
            error=case.find('error')
            skipped=case.find('skipped')
            if skipped is not None:
                summary['skipped']+=1;continue
            if fail is None and error is None:
                summary['passed']+=1;continue
            if fail is not None: summary['failed']+=1
            else: summary['errors']+=1
            defect=fail if fail is not None else error
            msg=(defect.attrib.get('message','') or '')+'\n'+(defect.text or '')[:1000]
            archival = (suite in HISTORIC_REASONS and
                        HISTORIC_REASONS[suite].lower() in msg.lower() and
                        'expected' in msg.lower() and 'was' in msg.lower())
            if archival:summary['archivalSourceMismatch']+=1
            else:summary['otherFailures']+=1
            summary['failures'].append({'class':suite,'test':case.attrib.get('name'),
                                        'kind':'ARCHIVAL_HASH_MISMATCH' if archival else 'CURRENT_OR_UNKNOWN_FAILURE',
                                        'message':defect.attrib.get('message','')[:350]})
    summary['fullUnfilteredGradleRegression']='FAIL' if summary['failed'] or summary['errors'] else 'PASS'
    summary['archivalChecksMayBeSeparatelyReconstructed']=bool(summary['archivalSourceMismatch'])
    summary['activeCodeWithoutHistoricMismatches']='NO_OTHER_FAILURES_RECORDED' if not summary['otherFailures'] else 'HAS_OTHER_FAILURES'
    return summary

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--junit',type=Path,required=True)
    ap.add_argument('--output',type=Path)
    args=ap.parse_args()
    data=classify(args.junit)
    render=json.dumps(data,ensure_ascii=False,indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(render,encoding='utf-8')
    print(render,end='')
    return 0
if __name__=='__main__':
    try:raise SystemExit(main())
    except (OSError,ET.ParseError,ValueError) as e:
        print('REGRESSION_CLASSIFICATION_ERROR:',e,file=sys.stderr);raise SystemExit(2)
