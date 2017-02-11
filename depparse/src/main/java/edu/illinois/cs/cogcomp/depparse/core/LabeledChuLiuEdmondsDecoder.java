package edu.illinois.cs.cogcomp.depparse.core;

import edu.cmu.cs.ark.cle.Arborescence;
import edu.cmu.cs.ark.cle.ChuLiuEdmonds;
import edu.cmu.cs.ark.cle.graph.DenseWeightedGraph;
import edu.cmu.cs.ark.cle.util.Weighted;
import edu.illinois.cs.cogcomp.depparse.features.LabeledDepFeatureGenerator;
import edu.illinois.cs.cogcomp.sl.core.AbstractFeatureGenerator;
import edu.illinois.cs.cogcomp.sl.core.AbstractInferenceSolver;
import edu.illinois.cs.cogcomp.sl.core.IInstance;
import edu.illinois.cs.cogcomp.sl.core.IStructure;
import edu.illinois.cs.cogcomp.sl.util.FeatureVectorBuffer;
import edu.illinois.cs.cogcomp.sl.util.WeightVector;

import java.util.*;

public class LabeledChuLiuEdmondsDecoder extends AbstractInferenceSolver {

    private static final long serialVersionUID = -7033487235520156024L;

    private static HashMap<String, HashSet<String>> deprelDict = new HashMap<>();
    private LabeledDepFeatureGenerator depfeat;

    public LabeledChuLiuEdmondsDecoder(AbstractFeatureGenerator featureGenerator) {
        this.depfeat = (LabeledDepFeatureGenerator) featureGenerator;
        assert depfeat != null;
    }

    /**
     * Side effect: update feature generator inside inference solver
     */
    public void updateInferenceSolver(DepInst inst) {
		// important, feature generator should see all features before inference
		depfeat.previewFeature(inst);

		for (int i = 1; i < inst.size(); i++) {
			String headPOS = inst.strPos[inst.heads[i]];
			String depPOS = inst.strPos[i];
			String keyPOS = headPOS + " " + depPOS;
			String headBrown = inst.strBrown[inst.heads[i]];
			String depBrown = inst.strBrown[i];
			String keyBrown = headBrown + " " + depBrown;

			deprelDict.computeIfAbsent(keyPOS, k -> new HashSet<>());
			if (!deprelDict.get(keyPOS).contains(inst.deprels[i]))
				deprelDict.get(keyPOS).add(inst.deprels[i]);

			deprelDict.computeIfAbsent(keyBrown, k -> new HashSet<>());
			if (!deprelDict.get(keyBrown).contains(inst.deprels[i]))
				deprelDict.get(keyBrown).add(inst.deprels[i]);
		}
	}

    private String predictLabel(int head, int node, DepInst ins, WeightVector weight) {
        if (head == -1)
            throw new IllegalArgumentException("Invalid arc, head must be positive!");

        String rel = null;
        float max = Float.NEGATIVE_INFINITY;
        String keyPOS = ins.strPos[head] + " " + ins.strPos[node];
        String keyBrown = ins.strBrown[head] + " " + ins.strBrown[node];
        if (deprelDict.get(keyPOS) == null && deprelDict.get(keyBrown) == null)
            rel = "UNDEFINED";
        else {
            Set<String> candidates = new HashSet<>();
            if (deprelDict.get(keyPOS) != null)
                candidates.addAll(deprelDict.get(keyPOS));
            if (deprelDict.get(keyBrown) != null)
                candidates.addAll(deprelDict.get(keyBrown));
            if (candidates.size() == 1)
                rel = candidates.iterator().next();
            else {
                for (String candidate : candidates) {
                    FeatureVectorBuffer edgefv =
                            depfeat.getLabeledEdgeFeatures(head, node, ins, candidate);
                    float decision = weight.dotProduct(edgefv.toFeatureVector(false));
                    if (decision > max) {
                        rel = candidate;
                        max = decision;
                    }
                }
            }
        }

        assert rel != null;
        return rel;
    }

    private void initEdge(double edgeScore[][], String edgeLabel[][]) {
        for (int i = 0; i < edgeScore.length; i++)
            for (int j = 0; j < edgeScore[0].length; j++) {
                edgeScore[i][j] = Float.NEGATIVE_INFINITY;
                edgeLabel[i][j] = "UNDEFINED";
            }
    }

    @Override
    public IStructure getBestStructure(WeightVector weight, IInstance ins) throws Exception {
        return getLossAugmentedBestStructure(weight, ins, null);
    }

    @Override
    public IStructure getLossAugmentedBestStructure(WeightVector weight, IInstance ins,
            IStructure goldStructure) throws Exception {
        DepInst sent = (DepInst) ins;
        DepStruct gold = goldStructure != null ? (DepStruct) goldStructure : null;
        // edgeScore[i][j] score of edge from head i to modifier j
        // i (head) varies from 0..n, while j (token idx) varies over 1..n
        double[][] edgeScore = new double[sent.size() + 1][sent.size() + 1];
        String[][] edgeLabel = new String[sent.size() + 1][sent.size() + 1];
        initEdge(edgeScore, edgeLabel);

        for (int head = 0; head <= sent.size(); head++) {
            for (int j = 1; j <= sent.size(); j++) {
                if (head == j) {
                    edgeScore[head][j] = Double.NEGATIVE_INFINITY;
                    continue;
                }
                String deprel = predictLabel(head, j, sent, weight);
                edgeLabel[head][j] = deprel;
                FeatureVectorBuffer edgefv = depfeat.getCombineEdgeFeatures(head, j, sent, deprel);

                // edge from head i to modifier j
                edgeScore[head][j] = weight.dotProduct(edgefv.toFeatureVector(false));
                if (gold != null) {
                    if (gold.heads[j] != head || !deprel.equals(gold.deprels[j])) // incur loss
                        edgeScore[head][j] += 1.0f;
                }
            }
        }
        return LabeledChuLiuEdmonds(edgeScore, edgeLabel);
    }


    /**
     * takes matrix[i][j] with directed edge i-->j scores and find the maximum aborescence using
     * Chu-Liu-Edmonds algorithm. Thanks to code from https://github.com/sammthomson/ChuLiuEdmonds
     */
    private DepStruct LabeledChuLiuEdmonds(double[][] edgeScore, String[][] edgeLabel) {
        DenseWeightedGraph<Integer> dg = DenseWeightedGraph.from(edgeScore);
        Weighted<Arborescence<Integer>> weightedSpanningTree =
                ChuLiuEdmonds.getMaxArborescence(dg, 0);

        Map<Integer, Integer> node2parent = weightedSpanningTree.val.parents;

        int[] head = new int[edgeScore.length];
        String[] deprel = new String[edgeScore.length];
        Arrays.fill(deprel, "");
        for (Integer node : node2parent.keySet()) {
            head[node] = node2parent.get(node);
            deprel[node] = edgeLabel[head[node]][node];
        }
        return new DepStruct(head, deprel);
    }

    @Override
    public float getLoss(IInstance ins, IStructure gold, IStructure pred) {
        float loss = 0.0f;
        DepStruct predDep = (DepStruct) pred;
        DepStruct goldDep = (DepStruct) gold;
        for (int i = 1; i < predDep.heads.length; i++) {
            if (predDep.heads[i] != goldDep.heads[i]
                    || !predDep.deprels[i].equals(goldDep.deprels[i])) {
                loss += 1.0f;
            }
        }

        return loss;
    }

    @Override
    public Object clone() {
        return new LabeledChuLiuEdmondsDecoder(depfeat);
    }
}
