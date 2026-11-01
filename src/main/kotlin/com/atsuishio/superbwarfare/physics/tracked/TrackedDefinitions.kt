package com.atsuishio.superbwarfare.physics.tracked

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

/**
 * Data-driven description of one tracked vehicle.
 *
 * Everything here describes *mechanism*, not a particular algorithm. A vehicle
 * never selects a hand-written physics branch; it only binds model bones and
 * mechanical numbers. When a mechanism cannot be expressed here the correct fix
 * is a new shared capability, never a per-vehicle exception.
 *
 * Coordinates follow the authored Bedrock frame used by the rest of the
 * project: model pixels, X positive to the vehicle's left, Y up, Z forward.
 * The compiler converts them to metres exactly once while applying [renderScale].
 */
@Serializable
data class TrackedVehicleDefinition(
    /** Bedrock geometry owning this vehicle, e.g. `superbwarfare:models/bedrock/vehicle/ztz_99a.geo.json`. */
    @SerialName("Model")
    var model: String = "",

    /**
     * The renderer multiplies the whole baked model by this factor before it is
     * drawn. Physics must use the same factor or the wheels stop lining up with
     * the rendered belt. Applied exactly once, and validated to a sane range.
     */
    @SerialName("RenderScale")
    var renderScale: Double = 1.0,

    /**
     * Model-space point (metres, physics frame) that the Bullet chassis body
     * origin corresponds to — normally the `Collision` OBB centre plus the
     * centre-of-mass offset. Every compiled mechanism point is expressed
     * relative to it, because that is the frame the physics body uses.
     */
    @SerialName("ChassisOrigin")
    var chassisOrigin: SerializedVec3 = Vec3.ZERO,

    /** Complete vehicle mass in kilograms, including every running-gear body. */
    @SerialName("MassKg")
    var massKg: Double = 55_000.0,

    @SerialName("Track")
    var track: TrackMechanismDefinition = TrackMechanismDefinition(),

    @SerialName("Left")
    var left: TrackSideDefinition = TrackSideDefinition(),

    @SerialName("Right")
    var right: TrackSideDefinition = TrackSideDefinition(),

    @SerialName("Drivetrain")
    var drivetrain: TrackDrivetrainDefinition = TrackDrivetrainDefinition(),

    /**
     * Local mesh terrain policy. This never selects raw AABB collision; it
     * only controls whether known rises may be replaced by Bullet ramps.
     */
    @SerialName("Terrain")
    var terrain: TrackedTerrainDefinition = TrackedTerrainDefinition(),

    /**
     * Solid hull boxes for the Bullet chassis, in the vehicle data frame
     * (metres, same frame as the vehicle `OBB` list). When present they replace
     * the hull parts derived from the gameplay OBBs for physics only; damage and
     * hit detection keep using the OBBs. They must stay clear of every wheel and
     * belt body, which the compiler checks.
     */
    @SerialName("HullCollision")
    var hullCollision: MutableList<TrackHullBoxDefinition> = mutableListOf(),
) {
    fun side(side: TrackedSide): TrackSideDefinition = when (side) {
        TrackedSide.LEFT -> left
        TrackedSide.RIGHT -> right
    }
}

/** Mechanical properties shared by both belts of one vehicle. */
@Serializable
data class TrackMechanismDefinition(
    /**
     * Pin-to-pin pitch in metres. This is a machine dimension, not the length of
     * the authored link cube: a shoe model may be longer, shorter, or split.
     */
    @SerialName("Pitch")
    var pitch: Double = 0.23307,

    /**
     * Distance from the pin axis to the link's inner wearing surface, in metres
     * along the link's local up axis. Pins near the inner face keep a plain
     * rectangular shoe from interpenetrating when the belt wraps inward.
     */
    @SerialName("PinInset")
    var pinInset: Double = 0.0,

    /**
     * Maximum rotation between two neighbouring links. The compiler also
     * reports the wrap angle the wheels actually demand, so this limit can be
     * audited against the mechanism instead of guessed.
     */
    @SerialName("AngularLimitDegrees")
    var angularLimitDegrees: Double = 40.0,

    /**
     * Declared link count per side. Zero means "use the solver's count"; that is
     * only acceptable while experimenting. An approved vehicle declares the
     * audited number explicitly.
     */
    @SerialName("LinkCount")
    var linkCount: Int = 0,

    /** Extra clearance added to the solved pin locus, in metres. */
    @SerialName("Clearance")
    var clearance: Double = 0.0,

    /**
     * Mass of a single shoe in kilograms. This is a real body in the simulation,
     * so it must be subtracted from the sprung mass. Zero means "unspecified";
     * the compiler then reports an error rather than inventing a share.
     */
    @SerialName("LinkMass")
    var linkMass: Double = 0.0,

    /** Physical belt topology; see [TrackTopology]. */
    @SerialName("Topology")
    var topology: TrackTopology = TrackTopology.WHEEL_PAIR_RUNS,

    /** Parameters of [TrackTopology.ANCHOR_CHAIN]; ignored by the other topology. */
    @SerialName("Chain")
    var chain: TrackChainDefinition = TrackChainDefinition(),

    /** Parameters of [TrackTopology.ENVELOPE_BELT]; ignored by the other topologies. */
    @SerialName("Envelope")
    var envelope: TrackEnvelopeDefinition = TrackEnvelopeDefinition(),
)

/** How the physical belt is built from the compiled mechanism. */
@Serializable
enum class TrackTopology {
    /** One rigid run per adjacent wheel pair (MCSP layout). */
    @SerialName("WheelPairRuns")
    WHEEL_PAIR_RUNS,

    /**
     * One closed belt from the side's envelope anchors; the bottom run is a hinged
     * chain of equal units that folds where terrain and suspension make it.
     * Requires `EnvelopeAnchorBone` on both sides.
     */
    @SerialName("AnchorChain")
    ANCHOR_CHAIN,

    /**
     * One closed belt fitted to the wheel envelope. Wrap arcs ride on the guide
     * wheels; the front incline, bottom, rear incline and top return are rigid
     * spans pinned end to end. The bottom starts as one body and is split and
     * merged at run time where terrain bends it. Anchors are only checked.
     */
    @SerialName("EnvelopeBelt")
    ENVELOPE_BELT,
}

/** Envelope belt parameters. Every value is data so a vehicle never needs code. */
@Serializable
data class TrackEnvelopeDefinition(
    /** Shortest bottom piece a split may leave, in visible link pitches. */
    @SerialName("MinPiecePitches")
    var minPiecePitches: Double = 2.0,

    /** Most bottom pieces one side may have at once. */
    @SerialName("MaxPieces")
    var maxPieces: Int = 13,

    /** Bend each pin may make away from its rest angle, degrees. */
    @SerialName("MaxBendDegrees")
    var maxBendDegrees: Double = 40.0,

    /** Ground kink that splits the bottom, degrees. */
    @SerialName("SplitDegrees")
    var splitDegrees: Double = 3.0,

    /** Pin bend below which two bottom pieces may merge again, degrees. */
    @SerialName("MergeDegrees")
    var mergeDegrees: Double = 1.0,

    /** A pin must stay straight and unneeded this long before it merges, seconds. */
    @SerialName("HoldSeconds")
    var holdSeconds: Double = 0.5,

    /** Ground farther below the belt than this is not in contact, metres. */
    @SerialName("ContactBand")
    var contactBand: Double = 0.15,

    /** Bullet collision margin of every belt body, metres; inside the belt thickness. */
    @SerialName("CollisionMargin")
    var collisionMargin: Double = 0.02,

    /** Multiplier on every span's rotational inertia. Statics are unchanged. */
    @SerialName("UnitInertiaScale")
    var unitInertiaScale: Double = 1.0,

    /** Bullet updates per physics substep requested while this vehicle exists. */
    @SerialName("SolverSubsteps")
    var solverSubsteps: Int = 1,

    /**
     * Solver iterations per Bullet update requested for the whole world while this
     * vehicle exists. The belt, the road wheels and the hull form a long joint chain;
     * the worst pin gap on a slope falls from 49 mm at Bullet's default 10 to 13 mm
     * at 40 and 7 mm at 60 (TrackedEnvelopeBeltNativeTest). It must be a world-wide
     * count, never a per-joint override, which leaves friction behind and makes a
     * parked vehicle creep.
     */
    @SerialName("SolverIterations")
    var solverIterations: Int = 10,

    /**
     * Belt tension, newtons. The belt does not circulate, so the rear span is not
     * pinned to the drive wheel: it slides along itself there and this tension
     * pulls it onto the sprocket, the way the top run's sag keeps a real belt
     * tight. The lower belt then follows the suspension without moving the idler.
     */
    @SerialName("Tension")
    var tension: Double = 20_000.0,

    /** Viscous damping of that slide, N·s/m. */
    @SerialName("TensionDamping")
    var tensionDamping: Double = 8_000.0,

    /** Slide travel each way from the fitted pose, metres. */
    @SerialName("TensionTravel")
    var tensionTravel: Double = 0.3,

)

/** Anchor chain parameters. Every value is data so a vehicle never needs code. */
@Serializable
data class TrackChainDefinition(
    /** Bottom unit length in visible link pitches. */
    @SerialName("UnitPitches")
    var unitPitches: Double = 2.0,

    /** Bend each pin may make away from its rest angle, degrees. */
    @SerialName("MaxBendDegrees")
    var maxBendDegrees: Double = 40.0,

    /** Pieces the top return run is cut into. */
    @SerialName("TopPieces")
    var topPieces: Int = 1,

    /**
     * Collision thickness of bottom units, metres; grows toward the belt interior
     * so the ground face stays on the belt surface. Null keeps the belt thickness.
     */
    @SerialName("BottomCollisionThickness")
    var bottomCollisionThickness: Double? = null,

    /** Multiplier on every unit's rotational inertia. Statics are unchanged. */
    @SerialName("UnitInertiaScale")
    var unitInertiaScale: Double = 1.0,

    /** Bullet updates per physics substep requested while this vehicle exists. */
    @SerialName("SolverSubsteps")
    var solverSubsteps: Int = 1,
)

/** One solid chassis collision box. [size] is half extents, metres. */
@Serializable
data class TrackHullBoxDefinition(
    @SerialName("Size")
    var size: SerializedVec3 = Vec3.ZERO,

    @SerialName("Position")
    var position: SerializedVec3 = Vec3.ZERO,

    /** Degrees, applied Y then X then Z like the vehicle OBB `CustomRotate`. */
    @SerialName("Rotation")
    var rotation: SerializedVec3 = Vec3.ZERO,
)

/** One side's running gear and its own link template. */
@Serializable
data class TrackSideDefinition(
    /**
     * Bone owning the single-link mesh used for every physical shoe, for
     * example `trackRotL0`. The compiler extracts that bone and its cubes.
     */
    @SerialName("LinkTemplateBone")
    var linkTemplateBone: String = "",

    /**
     * Optional bone-name prefix of the visible link bones for this side, for
     * example `trackRotL`. When absent no visible description is produced; the
     * physics layer never guesses a render template from bone names.
     */
    @SerialName("VisibleBonePrefix")
    var visibleBonePrefix: String? = null,

    @SerialName("Suspension")
    var suspension: TrackSuspensionDefinition = TrackSuspensionDefinition(),

    /**
     * Explicit belt traversal order, one wheel bone per entry, cyclic.
     *
     * A run is created between every adjacent pair **including last to first**,
     * so the order decides the physical topology. It is never derived from a Z
     * sort or from the wheel roles: a real belt can visit an idler, the road
     * wheels and the sprocket in an order only the vehicle knows.
     *
     * Required: a missing order is a compile error, because a topology that is
     * only implied cannot be audited.
     */
    @SerialName("BeltOrder")
    var beltOrder: MutableList<String> = mutableListOf(),

    /**
     * Mass budget for this side's belt runs, kg.
     *
     * Under mass option A this is the old shoe budget moved onto the runs, so the
     * vehicle total does not change when the topology does. Zero means "not
     * authored"; the compiler then falls back to `LinkMass * LinkCount` and warns,
     * because a run budget must not silently follow a shoe count.
     */
    @SerialName("RunMassBudgetKg")
    var runMassBudget: Double = 0.0,

    @SerialName("Wheels")
    var wheels: MutableList<TrackWheelDefinition> = mutableListOf(),

    /**
     * Optional bone whose cubes mark the belt envelope reference points.
     *
     * Each cube centre is one anchor on the belt centre line. Anchors are not
     * fixed: every anchor follows the wheel it is closest to, so the reference
     * envelope moves with suspension and tension. The bone itself is a marker and
     * is never rendered.
     */
    @SerialName("EnvelopeAnchorBone")
    var envelopeAnchorBone: String? = null,

    /**
     * When true every wheel body is centred laterally on the belt plane taken
     * from [linkTemplateBone], instead of on its own bone pivot. Use it when a
     * wheel bone's pivot is not on the visible disc (for example an arm pivot).
     * The bone offset is kept for rendering only.
     */
    @SerialName("AlignWheelsToBelt")
    var alignWheelsToBelt: Boolean = false,
)

/**
 * Which side of a wheel the belt wraps around.
 *
 * The belt runs outside the end wheels and under the road wheels, so the
 * envelope is not "always outside": an inside wrap bends the visible path the
 * other way and the physical run sits on the opposite side. It must be
 * authored per wheel.
 */
@Serializable
enum class TrackWrap {
    @SerialName("Outside")
    OUTSIDE,

    @SerialName("Inside")
    INSIDE,
}

/**
 * Wheel roles. The physics layer never infers a role from a bone name, an index
 * or a "front/rear" assumption: the definition states it.
 */
enum class TrackWheelRole {
    /** Sprung road wheel carrying the hull. */
    @SerialName("Support")
    SUPPORT,

    /** Sprocket that receives drive torque; may sit at either end. */
    @SerialName("DriveGuide")
    DRIVE_GUIDE,

    /** Passive end wheel; the one that can carry a tensioner. */
    @SerialName("IdlerGuide")
    IDLER_GUIDE,

    /** Optional fixed-axis return roller. Not sprung, does not touch the ground. */
    @SerialName("ReturnRoller")
    RETURN_ROLLER,
}

enum class TrackedSide { LEFT, RIGHT }

@Serializable
data class TrackWheelDefinition(
    /** Explicit model bone, e.g. `wheelL3`. Never resolved by traversal order. */
    @SerialName("Bone")
    var bone: String = "",

    @SerialName("Role")
    var role: TrackWheelRole = TrackWheelRole.SUPPORT,

    /**
     * Static load share used for spring preload. Relative weights are
     * normalised per side, so an uneven wheel base can be described without
     * hard-coding "divide by six".
     */
    @SerialName("LoadShare")
    var loadShare: Double = 1.0,

    @SerialName("Suspension")
    var suspension: TrackSuspensionOverride? = null,

    @SerialName("Tensioner")
    var tensioner: TrackTensionerDefinition? = null,

    /**
     * Which side of this wheel the belt wraps around.
     *
     * Null means "not authored". An approved vehicle states it for every belt
     * member; the validator rejects a missing value rather than silently treating
     * it as outside, because an inside wrap puts the belt on the other side of the
     * wheel.
     */
    @SerialName("Wrap")
    var wrap: TrackWrap? = null,

    /** Mass of this wheel body in kilograms. */
    @SerialName("Mass")
    var mass: Double = 0.0,

    /**
     * Rolling radius in metres. Authored explicitly: a wheel bone's subtree can
     * contain a suspension arm, so deriving the radius from mesh extents would
     * measure the arm instead of the tire.
     */
    @SerialName("Radius")
    var radius: Double = 0.0,

    /** Lateral half width in metres. */
    @SerialName("HalfWidth")
    var halfWidth: Double = 0.0,

    /**
     * Wheel axle direction in the chassis frame. A direction, not a position, so
     * both sides use `+X`. The S1 planar solver only accepts a lateral axle;
     * other directions are reported instead of being silently projected.
     */
    @SerialName("AxleDirection")
    var axleDirection: SerializedVec3 = Vec3(1.0, 0.0, 0.0),
)

/**
 * Spring/damper description. The compiler converts these to the SI quantities
 * the solver needs; the physics layer never reuses a "damping ratio" value as if
 * it were N·s/m.
 */
@Serializable
data class TrackSuspensionDefinition(
    @SerialName("TravelUp")
    var travelUp: Double = 0.18,

    @SerialName("TravelDown")
    var travelDown: Double = 0.24,

    /** Undamped natural frequency in Hz. */
    @SerialName("Frequency")
    var frequency: Double = 2.2,

    /**
     * Direct spring rate, N/m. MCSP authors `Spring` as a direct joint motor
     * stiffness instead of deriving it from a frequency.
     *
     * Null keeps this project's frequency/damping-ratio model. When present it
     * wins over [frequency] for the force law, and the effective frequency is
     * derived from this rate and the station mass rather than reported as the
     * authored nominal value. That keeps the audit from claiming a frequency
     * the spring does not have.
     */
    @SerialName("Spring")
    var springNPerM: Double? = null,

    /**
     * Direct viscous damping coefficient, N·s/m. MCSP has one `Damper` value;
     * SBW's split compression/rebound model stays the default.
     *
     * Null derives both rates from critical damping and the two ratios. When
     * present it is used for both directions, because a single authored number
     * cannot honestly be reported as two different ratios.
     */
    @SerialName("Damper")
    var damperNsPerM: Double? = null,

    /** Dimensionless compression damping ratio. */
    @SerialName("CompressionDampingRatio")
    var compressionDampingRatio: Double = 0.65,

    /** Dimensionless rebound damping ratio. */
    @SerialName("ReboundDampingRatio")
    var reboundDampingRatio: Double = 0.45,

    /** Hard limit for one wheel's spring row, in newtons. */
    @SerialName("MaxForce")
    var maxForce: Double = 400_000.0,

    /** Fraction of `travelUp` where the progressive bump stop starts. */
    @SerialName("BumpStopStart")
    var bumpStopStart: Double = 0.85,

    @SerialName("BumpStopExponent")
    var bumpStopExponent: Double = 2.0,

    /**
     * Suspension travel direction in the chassis frame. `+Y` is compression.
     * The S1 solver only accepts a vertical axis, so a canted suspension is
     * reported rather than approximated.
     */
    @SerialName("Direction")
    var direction: SerializedVec3 = Vec3(0.0, 1.0, 0.0),
)

@Serializable
data class TrackSuspensionOverride(
    @SerialName("TravelUp")
    var travelUp: Double? = null,

    @SerialName("TravelDown")
    var travelDown: Double? = null,

    @SerialName("Frequency")
    var frequency: Double? = null,

    @SerialName("Spring")
    var springNPerM: Double? = null,

    @SerialName("Damper")
    var damperNsPerM: Double? = null,

    @SerialName("CompressionDampingRatio")
    var compressionDampingRatio: Double? = null,

    @SerialName("ReboundDampingRatio")
    var reboundDampingRatio: Double? = null,

    @SerialName("MaxForce")
    var maxForce: Double? = null,

    @SerialName("BumpStopStart")
    var bumpStopStart: Double? = null,

    @SerialName("BumpStopExponent")
    var bumpStopExponent: Double? = null,

    @SerialName("Direction")
    var direction: SerializedVec3? = null,
) {
    fun applyTo(base: TrackSuspensionDefinition): TrackSuspensionDefinition = base.copy(
        travelUp = travelUp ?: base.travelUp,
        travelDown = travelDown ?: base.travelDown,
        frequency = frequency ?: base.frequency,
        springNPerM = springNPerM ?: base.springNPerM,
        damperNsPerM = damperNsPerM ?: base.damperNsPerM,
        compressionDampingRatio = compressionDampingRatio ?: base.compressionDampingRatio,
        reboundDampingRatio = reboundDampingRatio ?: base.reboundDampingRatio,
        maxForce = maxForce ?: base.maxForce,
        bumpStopStart = bumpStopStart ?: base.bumpStopStart,
        bumpStopExponent = bumpStopExponent ?: base.bumpStopExponent,
        direction = direction ?: base.direction,
    )
}

/**
 * Passive tensioner: a preload plus viscous damping acting on the idler along
 * the side's longitudinal axis, bounded by a hard mechanical travel.
 */
@Serializable
data class TrackTensionerDefinition(
    /** Symmetric travel around the authored bind position, in metres. */
    @SerialName("Travel")
    var travel: Double = 0.10,

    @SerialName("Preload")
    var preload: Double = 20_000.0,

    /** Viscous coefficient in N·s/m. */
    @SerialName("Damping")
    var damping: Double = 8_000.0,

    /**
     * Free-travel axis in the chassis frame. The S1 solver only moves the idler
     * along the belt's longitudinal axis, so any other axis is reported.
     */
    @SerialName("Axis")
    var axis: SerializedVec3 = Vec3(0.0, 0.0, 1.0),
)

@Serializable
data class TrackDrivetrainDefinition(
    @SerialName("MaxDriveTorque")
    var maxDriveTorque: Double = 160_000.0,

    @SerialName("MaxBrakeTorque")
    var maxBrakeTorque: Double = 320_000.0,

    @SerialName("MaxSpeed")
    var maxSpeed: Double = 15.6,

    /** Left/right differential split authored by the vehicle. */
    @SerialName("Differential")
    var differential: Double = 1.7,

    @SerialName("RollingResistance")
    var rollingResistance: Double = 0.015,

    @SerialName("Friction")
    var friction: Double = 0.8,

    /**
     * Longitudinal acceleration the drive may command, m/s².
     *
     * MCSP's `ChassisDrivetrain.Row.Accel` is a normalised-per-second slew bound
     * on the slot target, not a force. When null the controller keeps its
     * existing immediate target so an unauthored vehicle does not silently
     * change behaviour. This is not a confirmed 99A value.
     */
    @SerialName("Accel")
    var accelMps2: Double? = null,

    /** Longitudinal braking/deceleration slew bound, m/s². See [accelMps2]. */
    @SerialName("Brake")
    var brakeMps2: Double? = null,

    /**
     * Extra opposing-side speed at full steer, m/s.
     *
     * MCSP computes `target = throttle * maxSpeed ± steer * pivotSpeed` before
     * clamping. Null means "use [differential] exactly as before".
     */
    @SerialName("PivotSpeed")
    var pivotSpeedMps: Double? = null,

    /**
     * Seconds for the steering lever to travel its full 0..1 range.
     *
     * MCSP advances the lever by at most `dt / steerTime`. Null keeps the
     * current instantaneous lever.
     */
    @SerialName("SteerTime")
    var steerTimeSeconds: Double? = null,

    /**
     * Feed measured longitudinal traction back into the contact target while a
     * side is slipping, as MCSP does with `slotGrip = |traction|`.
     *
     * Off by default: a vehicle must opt in after its traction observation has
     * been calibrated, because enabling it on an unaudited mechanism can hide a
     * real contact problem. When disabled the controller still commands the
     * authored target; the measured traction remains available for diagnostics.
     */
    @SerialName("TractionLimit")
    var tractionLimit: Boolean = false,
)
