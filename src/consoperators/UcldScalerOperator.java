package consoperators;

import beast.core.Description;
import beast.core.Input;
import beast.core.parameter.RealParameter;
import beast.core.Operator;
import beast.math.distributions.LogNormalDistributionModel;
import beast.math.distributions.ParametricDistribution;
import beast.util.Randomizer;
import com.sun.org.apache.regexp.internal.RE;
import org.apache.commons.math.MathException;
import org.apache.commons.math3.special.Erf;
import org.apache.commons.math3.util.FastMath;
import org.omg.PortableInterceptor.SYSTEM_EXCEPTION;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;

@Description("For internal nodes: propose a new node time")
public class UcldScalerOperator extends Operator {
   final public Input<RealParameter> rateInput = new Input<>("rates", "the rates associated with nodes in the tree for sampling of individual rates among branches.",Input.Validate.REQUIRED);
    final public Input<RealParameter> stdevInput = new Input<>("stdev", "the distribution governing the rates among branches.");
    public final Input<Double> scaleFactorInput = new Input<>("scaleFactor", "scaling factor: larger means more bold proposals", 1.0);
    final public Input<ParametricDistribution> rateDistInput = new Input<>("distr", "the distribution governing the rates among branches.", Input.Validate.REQUIRED);



    private RealParameter rates;
    private RealParameter ucldStdev;

    private double m_fScaleFactor;

    protected ParametricDistribution distribution;
    private  LogNormalDistributionModel LN ;


    @Override
    public void initAndValidate() {
        rates = rateInput.get();
        m_fScaleFactor = scaleFactorInput.get();
        ucldStdev=stdevInput.get();
        distribution = rateDistInput.get();
    }

    @Override
    public double proposal() {
        double hastingsRatio;
        double new_stdev;
        double[] quantiles = new double[rates.getDimension()];
        double[] real_rates = new double[rates.getDimension()];

        if(distribution instanceof LogNormalDistributionModel) {
            LN = (LogNormalDistributionModel) distribution;
        }

        // Step1: calculate quantiles for real rates given the current ucldStdev
        //System.out.println("rates="+Arrays.toString(rateInput.get().getValues()));
        for (int idx = 0; idx < rates.getDimension(); idx++) {
            double r = rates.getValue(idx);
            real_rates[idx] = r;
            quantiles[idx] = getRateQuantiles(r);
        }
        //System.out.println("quantiles="+Arrays.toString(quantiles));

        // Step2: use scale operation to propose a new_ucldStdev
        double stdev = stdevInput.get().getValue();
        //System.out.println("S="+stdev);

        //double scale =  (m_fScaleFactor + (Randomizer.nextDouble() * ((1.0 / m_fScaleFactor) - m_fScaleFactor)));
        double scale = Randomizer.uniform(-0.5,0.5);

        new_stdev = stdev + scale;
        if (new_stdev <=0) {
            return Double.NEGATIVE_INFINITY;
        }
        //System.out.println("S'="+new_stdev);

        // set the new value
        ucldStdev.setValue(new_stdev);

        // return the log hastings ratio for scale operation
        hastingsRatio = 0;


        // Step3: calculate the new real rates under the proposed new_ucldStdev
        double [] rates_ = new double [rates.getDimension()];
        for (int idx = 0; idx < rates.getDimension(); idx++) {
            double r = real_rates[idx];
            double q = quantiles[idx];
            if (q == 0.0 || q == 1.0) {
                return Double.NEGATIVE_INFINITY;
            }
            double r_ = getRealRate(q);
            rates_[idx] = r_;

            hastingsRatio = hastingsRatio + getDicdf(r,q,stdev,new_stdev) ;

            rates.setValue(idx, r_);
        }
        //System.out.println("rates'="+Arrays.toString(rates_));

        //System.out.println("HR="+hastingsRatio);

        return hastingsRatio;
}

    private double getRateQuantiles (double r) {
        try {
            //System.out.println("S="+LN.SParameterInput.get()+",M="+LN.MParameterInput.get());
            return LN.cumulativeProbability(r);
        } catch (MathException e) {
            throw new RuntimeException("Failed to compute inverse cumulative probability because rate =" + r);
        }
    }

    private double getRealRate (double q) {
        try {
            //System.out.println("S'="+LN.SParameterInput.get()+",M="+LN.MParameterInput.get());
            return LN.inverseCumulativeProbability(q);
        } catch (MathException e) {
            throw new RuntimeException("Failed to compute inverse cumulative probability because quantile = " + q);
        }
    }

    private double getDicdf(double r, double q, double stdev, double new_stdev ) {
        double a1 = Math.log(1 + stdev * stdev);
        double a2 = Math.log(1 + new_stdev * new_stdev);
        double b1 = Math.log(1/(Math.sqrt(1 + stdev * stdev)));
        double b2 = Math.log(1/(Math.sqrt(1 + new_stdev * new_stdev)));
        double c = erfInv(2 * q - 1);
        double x_sq = Math.pow((Math.log(r) - b1), 2) / (2 * a1);
        double d = b2 + Math.sqrt(2 * a2) * c + c * c - x_sq;
        double e = r * Math.sqrt(a1);
        double f = Math.log(Math.sqrt(a2)/e);
        return (d + f);
    }

    /*
    Tuning the parameter: twindowsize represents the range of Uniform distribution
     */
    @Override
    public double getCoercableParameterValue() {
        return m_fScaleFactor;
    }

    @Override
    public void setCoercableParameterValue(final double value) {
        double upper = 1.0 - 1e-8;
        double lower = 1e-8;
        m_fScaleFactor = Math.max(Math.min(value, upper), lower);
    }

    /**
     * called after every invocation of this operator to see whether
     * a parameter can be optimised for better acceptance hence faster
     * mixing
     *
     * @param logAlpha difference in posterior between previous state & proposed state + hasting ratio
     */

    @Override
    public void optimize(double logAlpha) {
        // must be overridden by operator implementation to have an effect
        double delta = calcDelta(logAlpha);
        delta += Math.log(1.0 / m_fScaleFactor - 1.0);
        setCoercableParameterValue(1.0 / (Math.exp(delta) + 1.0));

    }

    @Override
    public final String getPerformanceSuggestion() {
        double prob = m_nNrAccepted / (m_nNrAccepted + m_nNrRejected + 0.0);
        double targetProb = getTargetAcceptanceProbability();

        double ratio = prob / targetProb;
        if (ratio > 2.0) ratio = 2.0;
        if (ratio < 0.5) ratio = 0.5;

        final double sf = Math.pow(m_fScaleFactor, ratio);

        DecimalFormat formatter = new DecimalFormat("#.###");
        if (prob < 0.10) {
            return "Try setting scaleFactor to about " + formatter.format(sf);
        } else if (prob > 0.40) {
            return "Try setting scaleFactor to about " + formatter.format(sf);
        } else return "";
    }

   private static double erfInv(final double x) {
    // beware that the logarithm argument must be
    // commputed as (1.0 - x) * (1.0 + x),
    // it must NOT be simplified as 1.0 - x * x as this
    // would induce rounding errors near the boundaries +/-1
             double w = - FastMath.log((1.0 - x) * (1.0 + x));
             double p;

             if (w < 6.25) {
                        w -= 3.125;
                        p =  -3.6444120640178196996e-21;
                        p =   -1.685059138182016589e-19 + p * w;
                        p =   1.2858480715256400167e-18 + p * w;
                        p =    1.115787767802518096e-17 + p * w;
                        p =   -1.333171662854620906e-16 + p * w;
                        p =   2.0972767875968561637e-17 + p * w;
                        p =   6.6376381343583238325e-15 + p * w;
                        p =  -4.0545662729752068639e-14 + p * w;
                        p =  -8.1519341976054721522e-14 + p * w;
                        p =   2.6335093153082322977e-12 + p * w;
                        p =  -1.2975133253453532498e-11 + p * w;
                        p =  -5.4154120542946279317e-11 + p * w;
                        p =    1.051212273321532285e-09 + p * w;
                        p =  -4.1126339803469836976e-09 + p * w;
                        p =  -2.9070369957882005086e-08 + p * w;
                        p =   4.2347877827932403518e-07 + p * w;
                        p =  -1.3654692000834678645e-06 + p * w;
                        p =  -1.3882523362786468719e-05 + p * w;
                        p =    0.0001867342080340571352 + p * w;
                        p =  -0.00074070253416626697512 + p * w;
                        p =   -0.0060336708714301490533 + p * w;
                        p =      0.24015818242558961693 + p * w;
                        p =       1.6536545626831027356 + p * w;
                    } else if (w < 16.0) {
                        w = FastMath.sqrt(w) - 3.25;
                        p =   2.2137376921775787049e-09;
                        p =   9.0756561938885390979e-08 + p * w;
                        p =  -2.7517406297064545428e-07 + p * w;
                        p =   1.8239629214389227755e-08 + p * w;
                        p =   1.5027403968909827627e-06 + p * w;
                        p =   -4.013867526981545969e-06 + p * w;
                        p =   2.9234449089955446044e-06 + p * w;
                      p =   1.2475304481671778723e-05 + p * w;
                        p =  -4.7318229009055733981e-05 + p * w;
                        p =   6.8284851459573175448e-05 + p * w;
                        p =   2.4031110387097893999e-05 + p * w;
                        p =   -0.0003550375203628474796 + p * w;
                        p =   0.00095328937973738049703 + p * w;
                        p =   -0.0016882755560235047313 + p * w;
                        p =    0.0024914420961078508066 + p * w;
                        p =   -0.0037512085075692412107 + p * w;
                        p =     0.005370914553590063617 + p * w;
                        p =       1.0052589676941592334 + p * w;
                        p =       3.0838856104922207635 + p * w;
                    } else if (!Double.isInfinite(w)) {
                        w = FastMath.sqrt(w) - 5.0;
                        p =  -2.7109920616438573243e-11;
                        p =  -2.5556418169965252055e-10 + p * w;
                        p =   1.5076572693500548083e-09 + p * w;
                        p =  -3.7894654401267369937e-09 + p * w;
                        p =   7.6157012080783393804e-09 + p * w;
                        p =  -1.4960026627149240478e-08 + p * w;
                        p =   2.9147953450901080826e-08 + p * w;
                        p =  -6.7711997758452339498e-08 + p * w;
                        p =   2.2900482228026654717e-07 + p * w;
                        p =  -9.9298272942317002539e-07 + p * w;
                        p =   4.5260625972231537039e-06 + p * w;
                        p =  -1.9681778105531670567e-05 + p * w;
                        p =   7.5995277030017761139e-05 + p * w;
                        p =  -0.00021503011930044477347 + p * w;
                       p =  -0.00013871931833623122026 + p * w;
                    p =       1.0103004648645343977 + p * w;
                       p =       4.8499064014085844221 + p * w;
                  } else {
                   p = Double.POSITIVE_INFINITY;
                 }

               return p * x;

          }


}
